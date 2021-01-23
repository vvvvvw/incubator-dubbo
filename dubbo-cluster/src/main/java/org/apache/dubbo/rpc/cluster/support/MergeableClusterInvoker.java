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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.cluster.Merger;
import org.apache.dubbo.rpc.cluster.merger.MergerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

//该类是分组聚合的实现类，其中最关机的就是invoke方法。
@SuppressWarnings("unchecked")
public class MergeableClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger log = LoggerFactory.getLogger(MergeableClusterInvoker.class);
    private ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("mergeable-cluster-executor", true));

    public MergeableClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    //前面部分在讲获得调用的结果，后面部分是对结果的合并，合并有两种方式，根据配置不同可用分为基于方法的合并和基于merger的合并。
    @Override
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        checkInvokers(invokers, invocation);
        // 得到配置的merger参数值
        String merger = getUrl().getMethodParameter(invocation.getMethodName(), Constants.MERGER_KEY);
        // 如果没有设置需要聚合，则只调用一个invoker的
        if (ConfigUtils.isEmpty(merger)) { // If a method doesn't have a merger, only invoke one Group
            for (final Invoker<T> invoker : invokers) {
                // 只要有一个可用就返回
                if (invoker.isAvailable()) {
                    try {
                        return invoker.invoke(invocation);
                    } catch (RpcException e) {
                        if (e.isNoInvokerAvailableAfterFilter()) {
                            //如果报错是因为对应group么有相关的invoker，则尝试其他的invoker
                            log.debug("No available provider for service" + directory.getUrl().getServiceKey() + " on group " + invoker.getUrl().getParameter(Constants.GROUP_KEY) + ", will continue to try another group.");
                        } else {
                            throw e;
                        }
                    }
                }
            }
            return invokers.iterator().next().invoke(invocation);
        }

        // 返回类型
        Class<?> returnType;
        try {
            // 获得返回类型
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes()).getReturnType();
        } catch (NoSuchMethodException e) {
            returnType = null;
        }

        // TODO: 分组聚合获取到的的invokers是每个group一个(当只有一个group但是有多个提供者的时候，会返回一个列表
        //  但是 后面会通过invoker.getUrl().getServiceKey() 来保证只返回一个提供者的返回值)
        // 结果集合
        Map<String, Future<Result>> results = new HashMap<String, Future<Result>>();
        // 循环invokers
        for (final Invoker<T> invoker : invokers) {
            // 获得每次调用的future
            Future<Result> future = executor.submit(new Callable<Result>() {
                @Override
                public Result call() throws Exception {
                    // 回调，把返回结果放入future
                    return invoker.invoke(new RpcInvocation(invocation, invoker));
                }
            });
            // 加入集合
            // serviceKey非常重要--serviceKey的值为: groupName/serviceInterface:version,
            // 如果version没有申明, serviceKey的值为: groupName/serviceInterface
            // 如果group没有申明, serviceKey的值为: serviceInterface:version
            // 如果version和group都没有申明, serviceKey的值为: serviceInterface
            results.put(invoker.getUrl().getServiceKey(), future);
        }

        Object result = null;

        List<Result> resultList = new ArrayList<Result>(results.size());

        // 获得超时时间
        int timeout = getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        // 遍历每一个结果
        for (Map.Entry<String, Future<Result>> entry : results.entrySet()) {
            Future<Result> future = entry.getValue();
            try {
                // 获得调用返回的结果
                Result r = future.get(timeout, TimeUnit.MILLISECONDS);
                if (r.hasException()) {
                    log.error("Invoke " + getGroupDescFromServiceKey(entry.getKey()) +
                                    " failed: " + r.getException().getMessage(),
                            r.getException());
                } else {
                    // 加入集合
                    resultList.add(r);
                }
            } catch (Exception e) {
                throw new RpcException("Failed to invoke service " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }

        // 如果为空，则返回空的结果
        if (resultList.isEmpty()) {
            return new RpcResult((Object) null);
        } else if (resultList.size() == 1) {
            // 如果只有一个结果，则返回该结果
            return resultList.iterator().next();
        }

        // 如果返回类型是void，也就是没有返回值，那么返回空结果
        if (returnType == void.class) {
            return new RpcResult((Object) null);
        }

        // 根据方法来合并，将调用返回结果的指定方法进行合并
        if (merger.startsWith(".")) {
            merger = merger.substring(1);
            Method method;
            try {
                // 获得方法
                //根据 returnType的指定方法来合并
                method = returnType.getMethod(merger, returnType);
            } catch (NoSuchMethodException e) {
                throw new RpcException("Can not merge result because missing method [ " + merger + " ] in class [ " +
                        returnType.getClass().getName() + " ]");
            }
            // 有 Method ，进行合并
            if (!Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }
            result = resultList.remove(0).getValue();
            try {
                if (method.getReturnType() != void.class
                        && method.getReturnType().isAssignableFrom(result.getClass())) {
                    //如果合并方法的返回值和 原始返回值类型一致，合并时，修改 result
                    for (Result r : resultList) {
                        result = method.invoke(result, r.getValue());
                    }
                } else {
                    // 方法返回类型不匹配，合并时，不修改 result
                    for (Result r : resultList) {
                        method.invoke(result, r.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RpcException("Can not merge result: " + e.getMessage(), e);
            }
        } else {
            // 基于 Merger
            Merger resultMerger;
            // 如果是默认的方式
            if (ConfigUtils.isDefault(merger)) {
                // 获得该类型的合并方式
                resultMerger = MergerFactory.getMerger(returnType);
            } else {
                // 如果不是默认的，则配置中指定获得Merger的实现类
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }
            if (resultMerger != null) {
                List<Object> rets = new ArrayList<Object>(resultList.size());
                // 遍历返回结果
                for (Result r : resultList) {
                    // 加入到rets
                    rets.add(r.getValue());
                }
                // 加入到rets
                result = resultMerger.merge(
                        rets.toArray((Object[]) Array.newInstance(returnType, 0)));
            } else {
                throw new RpcException("There is no merger to merge result.");
            }
        }
        // 返回结果
        return new RpcResult(result);
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
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        directory.destroy();
    }

    private String getGroupDescFromServiceKey(String key) {
        int index = key.indexOf("/");
        if (index > 0) {
            return "group [ " + key.substring(0, index) + " ]";
        }
        return key;
    }
}
