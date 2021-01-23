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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerMethodModel;
import org.apache.dubbo.rpc.model.ConsumerModel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * EventFilter
 */
//回调方法filter
@Activate(group = Constants.CONSUMER)
public class FutureFilter implements Filter {

    protected static final Logger logger = LoggerFactory.getLogger(FutureFilter.class);

    @Override
    public Result invoke(final Invoker<?> invoker, final Invocation invocation) throws RpcException {
        // oninvoke回调
        fireInvokeCallback(invoker, invocation);
        // need to configure if there's return value before the invocation in order to help invoker to judge if it's
        // necessary to return future.
        // 该方法是真正的调用方法的执行
        return invoker.invoke(invocation);
    }

    @Override
    public Result onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        if (result instanceof AsyncRpcResult) {
            AsyncRpcResult asyncResult = (AsyncRpcResult) result;
            asyncResult.thenApplyWithContext(r -> {
                asyncCallback(invoker, invocation, r);
                return r;
            });
            return asyncResult;
        } else {
            syncCallback(invoker, invocation, result);
            return result;
        }
    }

    //同步回调
    private void syncCallback(final Invoker<?> invoker, final Invocation invocation, final Result result) {
        // 如果有异常
        if (result.hasException()) {
            // 则调用异常的结果处理
            fireThrowCallback(invoker, invocation, result.getException());
        } else {
            // 调用正常的结果处理
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    private void asyncCallback(final Invoker<?> invoker, final Invocation invocation, Result result) {
        if (result.hasException()) {
            fireThrowCallback(invoker, invocation, result.getException());
        } else {
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    //请求之前 回调方法，如果出现异常，则调用 异常回调方法
    private void fireInvokeCallback(final Invoker<?> invoker, final Invocation invocation) {
        final ConsumerMethodModel.AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }
        // 获得在真实调用远程服务之前回调的方法
        final Method onInvokeMethod = asyncMethodInfo.getOninvokeMethod();
        // 获得在真实调用远程服务之前回调的对象
        final Object onInvokeInst = asyncMethodInfo.getOninvokeInstance();

        if (onInvokeMethod == null && onInvokeInst == null) {
            return;
        }
        if (onInvokeMethod == null || onInvokeInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a oninvoke callback config , but no such " + (onInvokeMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        // 如果不可以访问，则设置为可访问
        if (!onInvokeMethod.isAccessible()) {
            onInvokeMethod.setAccessible(true);
        }

        // 获得参数数组
        Object[] params = invocation.getArguments();
        try {
            // 调用方法
            onInvokeMethod.invoke(onInvokeInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    //正常的请求之后返回结果的处理
    private void fireReturnCallback(final Invoker<?> invoker, final Invocation invocation, final Object result) {
        final ConsumerMethodModel.AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }

        final Method onReturnMethod = asyncMethodInfo.getOnreturnMethod();
        final Object onReturnInst = asyncMethodInfo.getOnreturnInstance();

        //not set onreturn callback
        if (onReturnMethod == null && onReturnInst == null) {
            return;
        }

        if (onReturnMethod == null || onReturnInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onReturnMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onReturnMethod.isAccessible()) {
            onReturnMethod.setAccessible(true);
        }

        Object[] args = invocation.getArguments();
        Object[] params;
        // 获得返回结果类型
        Class<?>[] rParaTypes = onReturnMethod.getParameterTypes();
        // 设置参数和返回结果
        if (rParaTypes.length > 1) {
            //如果只有两个参数，且第二个参数是 Object[]类型，则将 返回结果赋给第一个参数，args赋给 第二个参数
            if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                params = new Object[2];
                params[0] = result;
                params[1] = args;
            } else {
                //否则，第一个参数为返回结果，后面依次赋值参数
                params = new Object[args.length + 1];
                params[0] = result;
                System.arraycopy(args, 0, params, 1, args.length);
            }
        } else {
            //如果只有一个参数，则第一个参数为 返回结果
            params = new Object[]{result};
        }
        try {
            // 调用方法
            onReturnMethod.invoke(onReturnInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    //在请求远程服务之前出现异常的回调方法 或者 请求远程服务异常的回调方法
    private void fireThrowCallback(final Invoker<?> invoker, final Invocation invocation, final Throwable exception) {
        final ConsumerMethodModel.AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }

        final Method onthrowMethod = asyncMethodInfo.getOnthrowMethod();
        final Object onthrowInst = asyncMethodInfo.getOnthrowInstance();

        //onthrow callback not configured
        if (onthrowMethod == null && onthrowInst == null) {
            return;
        }
        if (onthrowMethod == null || onthrowInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onthrow callback config , but no such " + (onthrowMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onthrowMethod.isAccessible()) {
            onthrowMethod.setAccessible(true);
        }
        // 获得抛出异常的类型
        Class<?>[] rParaTypes = onthrowMethod.getParameterTypes();
        //如果第一个参数是异常
        if (rParaTypes[0].isAssignableFrom(exception.getClass())) {
            try {
                Object[] args = invocation.getArguments();
                Object[] params;

                // 把类型和抛出的异常值放入返回结果
                if (rParaTypes.length > 1) {
                    if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                        //如果只有两个参数，且第二个参数是 Object[]类型，则将 异常赋给第一个参数，args赋给 第二个参数
                        params = new Object[2];
                        params[0] = exception;
                        params[1] = args;
                    } else {
                        //否则，第一个参数为异常类型，后面依次赋值参数
                        params = new Object[args.length + 1];
                        params[0] = exception;
                        System.arraycopy(args, 0, params, 1, args.length);
                    }
                } else {
                    //如果只有一个参数，则第一个参数为 抛出的异常
                    params = new Object[]{exception};
                }
                // 调用异常回调方法
                onthrowMethod.invoke(onthrowInst, params);
            } catch (Throwable e) {
                logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), e);
            }
        } else {
            logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), exception);
        }
    }

    //找到回调信息
    private ConsumerMethodModel.AsyncMethodInfo getAsyncMethodInfo(Invoker<?> invoker, Invocation invocation) {
        final ConsumerModel consumerModel = ApplicationModel.getConsumerModel(invoker.getUrl().getServiceKey());
        if (consumerModel == null) {
            return null;
        }

        String methodName = invocation.getMethodName();
        if (methodName.equals(Constants.$INVOKE)) {
            //如果是泛化调用，方法名就是第一个参数
            methodName = (String) invocation.getArguments()[0];
        }

        //todo 根据方法名获取第一个方法，会不会有问题
        ConsumerMethodModel methodModel = consumerModel.getMethodModel(methodName);
        if (methodModel == null) {
            return null;
        }

        final ConsumerMethodModel.AsyncMethodInfo asyncMethodInfo = methodModel.getAsyncInfo();
        if (asyncMethodInfo == null) {
            return null;
        }

        return asyncMethodInfo;
    }
}
