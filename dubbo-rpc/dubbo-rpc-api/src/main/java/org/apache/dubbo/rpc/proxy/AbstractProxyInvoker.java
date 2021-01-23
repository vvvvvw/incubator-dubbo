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
package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.AsyncContextImpl;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;

/**
 * InvokerWrapper
 */
//该类实现了Invoker接口，是代理invoker对象的抽象类。
public abstract class AbstractProxyInvoker<T> implements Invoker<T> {
    Logger logger = LoggerFactory.getLogger(AbstractProxyInvoker.class);

    //服务端：外部传入的服务实现类(是wrapper之前的)
    private final T proxy;

    //服务端：服务接口
    private final Class<T> type;

    // 服务端：这个url其实是暴露协议的url，其中protocol为 暴露协议，host和端口为暴露到外部的主机名和端口，其他查询参数也是从 各个配置类 根据混合配置 最终组装成的
    // 如果是 injvm的话，injvm://127.0.0.1:0//[协议的contextPath/]path(默认是接口权限定类名)？ServiceConfig组装出来的其他查询参数
    // 如果是 暴露到注册中心url的话，registry://注册中心ip+端口/org.apache.dubbo.registry.RegistryService?registry={注册中心协议(如果从注册中心地址获取不到协议，默认dubbo)}&export={服务url的toString(其中包括serviceConfig组装出来的各种参数)}&proxy={服务url中的代理参数}和applicationConfig、RegistryConfig中获取的其他查询参数 服务url：{暴露协议的扩展名}://{需要注册到注册中心的ip地址}:{需要注册到注册中心的端口}/[协议的contextPath/]path(默认是接口权限定类名)？ServiceConfig组装出来的其他查询参数
    // 如果没有配置中心的话，则是 服务类url：{暴露协议的扩展名}://{需要注册到注册中心的ip地址}:{需要注册到注册中心的端口}/[协议的contextPath/]path(默认是接口权限定类名)？ServiceConfig组装出来的其他查询参数
    private final URL url;

    public AbstractProxyInvoker(T proxy, Class<T> type, URL url) {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy == null");
        }
        if (type == null) {
            throw new IllegalArgumentException("interface == null");
        }
        if (!type.isInstance(proxy)) {
            throw new IllegalArgumentException(proxy.getClass().getName() + " not implement interface " + type);
        }
        this.proxy = proxy;
        this.type = type;
        this.url = url;
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
    }

    // TODO Unified to AsyncResult?
    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        RpcContext rpcContext = RpcContext.getContext();
        try {
            // 调用了抽象方法doInvoke
            Object obj = doInvoke(proxy, invocation.getMethodName(), invocation.getParameterTypes(), invocation.getArguments());
            // TODO: future_returntype的标识会从客户端传递到服务端么？如果传递的话岂不是服务端返回的也是这个CompletableFuture  by 15258 2019/6/6 7:27
            if (RpcUtils.isReturnTypeFuture(invocation)) {
                //如果是异步调用方式，返回异步对象
                return new AsyncRpcResult((CompletableFuture<Object>) obj);
            } else if (rpcContext.isAsyncStarted()) { // ignore obj in case of RpcContext.startAsync()? always rely on user to write back.
                // TODO: 服务端有异步么？有的话 有什么用？  by 15258 2019/6/6 7:29
                return new AsyncRpcResult(((AsyncContextImpl)(rpcContext.getAsyncContext())).getInternalFuture());
            } else {
                return new RpcResult(obj);
            }
        } catch (InvocationTargetException e) {
            // TODO async throw exception before async thread write back, should stop asyncContext
            if (rpcContext.isAsyncStarted() && !rpcContext.stopAsync()) {
                logger.error("Provider async started, but got an exception from the original method, cannot write the exception back to consumer because an async result may have returned the new thread.", e);
            }
            return new RpcResult(e.getTargetException());
        } catch (Throwable e) {
            throw new RpcException("Failed to invoke remote proxy method " + invocation.getMethodName() + " to " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected abstract Object doInvoke(T proxy, String methodName, Class<?>[] parameterTypes, Object[] arguments) throws Throwable;

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? " " : getUrl().toString());
    }


}
