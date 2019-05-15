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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * Protocol. (API/SPI, Singleton, ThreadSafe)
 */
//该接口是服务域接口，也是协议接口，它是一个可扩展的接口，默认实现的是
// dubbo协议。定义了四个方法，关键的是服务暴露和引用两个方法。
@SPI("dubbo")
public interface Protocol {

    /**
     * Get default port when user doesn't config the port.
     * 获得默认的端口
     * @return default port
     */
    int getDefaultPort();

    /**
     * Export service for remote invocation: <br>
     * 1. Protocol should record request source address after receive a request:
     * RpcContext.getContext().setRemoteAddress();<br>
     * 2. export() must be idempotent, that is, there's no difference between invoking once and invoking twice when
     * export the same URL<br>
     * 3. Invoker instance is passed in by the framework, protocol needs not to care <br>
     * 暴露服务方法，
     * 1.Protocol在接收到请求以后应该记录下请求的来源地址：RpcContext.getContext().setRemoteAddress();
     * 2.export()应该是幂等的，也就是说，如果export相同的url调用调用该方法没有任何区别
     * 3. Invoker实例应该通过框架传递过来，protocol不需要关心
     * @param <T>     Service type 服务类型
     * @param invoker Service invoker 服务的实体域
     * @return exporter reference for exported service, useful for unexport the service later
     * @throws RpcException thrown when error occurs during export the service, for example: port is occupied
     */
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;

    /**
     * Refer a remote service: <br>
     * 1. When user calls `invoke()` method of `Invoker` object which's returned from `refer()` call, the protocol
     * needs to correspondingly execute `invoke()` method of `Invoker` object <br>
     * 2. It's protocol's responsibility to implement `Invoker` which's returned from `refer()`. Generally speaking,
     * protocol sends remote request in the `Invoker` implementation. <br>
     * 3. When there's check=false set in URL, the implementation must not throw exception but try to recover when
     * connection fails.
     * 引用服务方法
     * fixme 1.当用户调用 Invoker(通过refer()方法返回的)的invoke()时，protocol需要相应地执行 Invoker.invoke()方法
     * 2.protocols的职责是 用来实现 refer方法返回的 invoker，总的来说，protocols在Invoker的实现类中用来发送远程调用
     * 3.当 url属性check=false时，本实现不会抛出异常，但是需要在连接失败的情况下重新建立连接
     * @param <T>  Service type 服务类型
     * @param type Service class 服务类名
     * @param url  URL address for the remote service
     * @return invoker service's local proxy
     * @throws RpcException when there's any error while connecting to the service provider
     */
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException;

    /**
     * Destroy protocol: <br>
     *  销毁协议
     *  1.取消所有发布和引用的protocol
     *  2.释放占用的资源，比如:连接、端口
     *  3.即使protocol已经被销毁，也可以继续发布和引用新服务
     * 1. Cancel all services this protocol exports and refers <br>
     * 2. Release all occupied resources, for example: connection, port, etc. <br>
     * 3. Protocol can continue to export and refer new service even after it's destroyed.
     */
    void destroy();

}