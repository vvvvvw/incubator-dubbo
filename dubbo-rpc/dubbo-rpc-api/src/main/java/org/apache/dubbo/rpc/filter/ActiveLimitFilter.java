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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcStatus;

/**
 * ActiveLimitFilter restrict the concurrent client invocation for a service or service's method from client side.
 * To use active limit filter, configured url with <b>actives</b> and provide valid >0 integer value.
 * <pre>
 *     e.g. <dubbo:reference id="demoService" check="false" interface="org.apache.dubbo.demo.DemoService" "actives"="2"/>
 *      In the above example maximum 2 concurrent invocation is allowed.
 *      If there are more than configured (in this example 2) is trying to invoke remote method, then rest of invocation
 *      will wait for configured timeout(default is 0 second) before invocation gets kill by dubbo.
 * </pre>
 *
 * @see Filter
 */
//该类时对于每个服务的每个方法的最大可并行调用数量限制的过滤器，它是在服务消费者侧的过滤。
@Activate(group = Constants.CONSUMER, value = Constants.ACTIVES_KEY)
public class ActiveLimitFilter implements Filter {

    //该过滤器是用来限制调用数量，先进行调用数量的检测，如果没有到达最大的调用数量，
    // 则先调用后面的调用链，如果在后面的调用链失败，则记录相关时间，如果成功也记录相关时间和调用次数。
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得url对象
        URL url = invoker.getUrl();
        // 获得方法名称
        String methodName = invocation.getMethodName();
        // 获得并发调用数（单个服务的单个方法），默认为0
        int max = invoker.getUrl().getMethodParameter(methodName, Constants.ACTIVES_KEY, 0);
        // 通过方法名来获得对应的状态
        RpcStatus count = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        if (!count.beginCount(url, methodName, max)) {
            // 如果活跃数量大于等于最大的并发调用数量
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, 0);
            long start = System.currentTimeMillis();
            long remain = timeout;
            synchronized (count) {
                while (!count.beginCount(url, methodName, max)) {
                    try {
                        // 最大等待时间:remain，在其他线程处理完以后会被唤醒
                        count.wait(remain);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    // 获得累计时间
                    long elapsed = System.currentTimeMillis() - start;
                    remain = timeout - elapsed;
                    // 如果累计时间大于超时时间，则抛出异常
                    if (remain <= 0) {
                        throw new RpcException("Waiting concurrent invoke timeout in client-side for service:  "
                                + invoker.getInterface().getName() + ", method: "
                                + invocation.getMethodName() + ", elapsed: " + elapsed
                                + ", timeout: " + timeout + ". concurrent invokes: " + count.getActive()
                                + ". max concurrent invoke limit: " + max);
                    }
                }
            }
        }

        boolean isSuccess = true;
        // 获得系统时间作为开始时间
        long begin = System.currentTimeMillis();
        try {
            // 调用后面的调用链，如果没有抛出异常，则算成功
            return invoker.invoke(invocation);
        } catch (RuntimeException t) {
            isSuccess = false;
            throw t;
        } finally {
            // 结束计数，记录时间
            count.endCount(url, methodName, System.currentTimeMillis() - begin, isSuccess);
            if (max > 0) {
                synchronized (count) {
                    //处理完，// 唤醒count
                    count.notifyAll();
                }
            }
        }
    }
}
