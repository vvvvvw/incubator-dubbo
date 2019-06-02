/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractClusterInvoker
 */
/*
该类实现了Invoker接口，是集群Invoker的抽象类。
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractClusterInvoker.class);

    // 目录，包含多个invoker
    protected final Directory<T> directory;

    //是否需要核对可用
    protected final boolean availablecheck;

    //是否销毁
    private AtomicBoolean destroyed = new AtomicBoolean(false);

    //粘滞连接的Invoker
    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        this.directory = directory;
        //当availablecheck为true时，总是应该调用invoker.isAvailable()来检查连接是否可用
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a) Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * <p>
     * b) Reselection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * @param loadbalance load balance policy
     * @param invocation  invocation
     * @param invokers    invoker candidates
     * @param selected    exclude selected invokers or not 如果在未使用的Invoker找不到客户端，也会从selected中选择
     * @return the invoker which will final to do invoke.
     * @throws RpcException exception
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        // 如果invokers为空，则返回null
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        // 获得方法名
        String methodName = invocation == null ? StringUtils.EMPTY : invocation.getMethodName();

        // 是否启动了粘滞连接
        boolean sticky = invokers.get(0).getUrl()
                .getMethodParameter(methodName, Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY);

        //ignore overloaded method
        // 如果上一次粘滞连接的调用不在可选的提供者列合内，则直接设置为空
        if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
            stickyInvoker = null;
        }
        //ignore concurrency problem
        // TODO: 为什么需要强制校验可达性才能使用   by 15258 2019/5/31 9:05
        // stickyInvoker不为null,并且没在已选列表中并且强制校验可达性且可达性校验成功，则返回上次的服务提供者stickyInvoker
        // 由于stickyInvoker不能包含在selected列表中，通过代码看，可以得知是forking和failover集群策略，用不了sticky属性
        if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
            if (availablecheck && stickyInvoker.isAvailable()) {
                return stickyInvoker;
            }
        }

        // 利用负载均衡选一个提供者
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        // 如果启动粘滞连接，则记录这一次的调用
        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }

    /*
    该方法是用负载均衡选择一个invoker的主要逻辑。
    该方法实现了使用负载均衡策略选择一个调用者。首先，使用loadbalance
    选择一个调用者。如果此调用者位于先前选择的列表中，或者如果此调用者不可用，
    则重新选择，否则返回第一个选定的调用者。重新选择，重选的验证规则：选择>可用。
    这条规则可以保证所选的调用者最少
    有机会成为之前选择的列表中的一个，也是保证这个调用程序可用。
     */
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        // 如果只有一个 ，就直接返回这个
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        // 调用负载均衡选择
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        // 如果选择的提供者，已在selected中或者强制校验并不可用则重新选择
        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                // TODO: 这边没有排除掉选择失败的，那要是又选择到 已经包含在selected中的呢？  by 15258 2019/5/31 9:18
                Invoker<T> rInvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rInvoker != null) {
                    invoker = rInvoker;
                } else {
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    // 如果重新选择失败，看下第一次选的位置，如果不是最后，选+1位置.
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        // 最后再避免选择到同一个invoker
                        invoker = invokers.get((index + 1) % invokers.size());
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`,
     * just pick an available one using loadbalance policy.
     *
     * @param loadbalance    load balance policy
     * @param invocation     invocation
     * @param invokers       invoker candidates
     * @param selected       exclude selected invokers or not
     * @param availablecheck check invoker available if true
     * @return the reselect result to do invoke
     * @throws RpcException exception
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck) throws RpcException {

        //预先分配一个重选列表，这个列表是一定会用到的.
        //Allocating one in advance, this list is certain to be used.
        List<Invoker<T>> reselectInvokers = new ArrayList<>(
                invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        // First, try picking a invoker not in `selected`.
        for (Invoker<T> invoker : invokers) {
            if (availablecheck && !invoker.isAvailable()) {
                continue;
            }

            //从非select中选
            //把不包含在selected中的提供者，放入重选列表reselectInvokers，让负载均衡器选择
            if (selected == null || !selected.contains(invoker)) {

                reselectInvokers.add(invoker);
            }
        }

        if (!reselectInvokers.isEmpty()) {
            // 在重选列表中用负载均衡器选择
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        //如果没有 校验连接 成功并且不在 selectedlist中的invoker
        // Just pick an available invoker using loadbalance policy
        if (selected != null) {
            for (Invoker<T> invoker : selected) {
                //使用校验成功 但是 selectedlist中的invoker
                if ((invoker.isAvailable()) // available first
                        && !reselectInvokers.contains(invoker)) {
                    // 不核对服务是否可以，把不包含在selected中的提供者，放入重选列表reselectInvokers，让负载均衡器选择
                    reselectInvokers.add(invoker);
                }
            }
        }
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        return null;
    }

    //该方法是invoker接口必备的方法，调用链的逻辑，不过主要的逻辑在doInvoke方法中，
    // 该方法是该类的抽象方法，让子类只关注doInvoke方法。
    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        // 核对是否已经销毁
        checkWhetherDestroyed();

        // 获得上下文的附加值
        // binding attachments into invocation.
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        // 把附加值放入到会话域中
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }

        // 生成服务提供者集合
        List<Invoker<T>> invokers = list(invocation);
        // 获得负载均衡器
        LoadBalance loadbalance = initLoadBalance(invokers, invocation);
        //如果自动添加invocationid，并且现在还没有添加并且 inv是 RpcInvocation的实例，则添加
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {
        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            throw new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    //调用了directory的list方法，从会话域中获得所有的Invoker集合。
    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        // 把会话域中的invoker加入集合
        return directory.list(invocation);
    }

    /**
     * Init LoadBalance.
     * <p>
     * if invokers is not empty, init from the first invoke's url and invocation
     * if invokes is empty, init a default LoadBalance(RandomLoadBalance)
     * </p>
     *
     * @param invokers   invokers
     * @param invocation invocation
     * @return LoadBalance instance. if not need init, return null.
     */
    protected LoadBalance initLoadBalance(List<Invoker<T>> invokers, Invocation invocation) {
        // 如果没有指定用哪个负载均衡策略，则默认用随机负载均衡策略
        if (CollectionUtils.isNotEmpty(invokers)) {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        } else {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }
    }
}
