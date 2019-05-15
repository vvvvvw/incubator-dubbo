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
 * The maximum parallel execution request count per method per service for the provider.If the max configured
 * <b>executes</b> is set to 10 and if invoke request where it is already 10 then it will throws exception. It
 * continue the same behaviour un till it is <10.
 *
 */
//该过滤器是限制最大可并行执行请求数，该过滤器是服务提供者侧
@Activate(group = Constants.PROVIDER, value = Constants.EXECUTES_KEY)
public class ExecuteLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得url对象
        URL url = invoker.getUrl();
        // 方法名称
        String methodName = invocation.getMethodName();
        int max = url.getMethodParameter(methodName, Constants.EXECUTES_KEY, 0);
        // 如果该方法设置了executes并且值大于0
        if (!RpcStatus.beginCount(url, methodName, max)) {
            //超出数量，直接抛出异常
            throw new RpcException("Failed to invoke method " + invocation.getMethodName() + " in provider " +
                    url + ", cause: The service using threads greater than <dubbo:service executes=\"" + max +
                    "\" /> limited.");
        }

        long begin = System.currentTimeMillis();
        boolean isSuccess = true;
        try {
            // 调用下一个调用链
            return invoker.invoke(invocation);
        } catch (Throwable t) {
            isSuccess = false;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new RpcException("unexpected exception when ExecuteLimitFilter", t);
            }
        } finally {
            // 计数减1
            RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, isSuccess);
        }
    }

}
