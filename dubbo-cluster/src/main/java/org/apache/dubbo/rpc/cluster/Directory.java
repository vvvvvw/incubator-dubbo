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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.Node;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.support.Cluster;

import java.util.List;

/**
 * Directory. (SPI, Prototype, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Directory_service">Directory Service</a>
 *
 * @see Cluster#join(Directory)
 */
//Directory可以看成是多个Invoker的集合，但是它的值会随着注册中心中服务变化推送而动态变化，那么Invoker以及如何动态变化就是一个重点内容。
public interface Directory<T> extends Node {

    /**
     * get service type.
     *  获得服务类型
     * @return service type.
     */
    Class<T> getInterface();

    /**
     * list invokers.
     * 获得所有服务Invoker集合
     * @return invokers
     */
    List<Invoker<T>> list(Invocation invocation) throws RpcException;

}