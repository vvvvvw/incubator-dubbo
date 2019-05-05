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
package org.apache.dubbo.remoting;

import java.net.InetSocketAddress;

/**
 * Channel. (API/SPI, Prototype, ThreadSafe)
 *
 * @see org.apache.dubbo.remoting.Client
 * @see org.apache.dubbo.remoting.Server#getChannels()
 * @see org.apache.dubbo.remoting.Server#getChannel(InetSocketAddress)
 */
//该接口是通道接口，通道是通讯的载体。
// channel可以读也可以写，并且可以异步读写。channel是client和server的传输桥梁。channel和client是一一对应的，也就是一个client对应一个channel，
// 但是channel和server是多对一对关系，也就是一个server可以对应多个channel。
public interface Channel extends Endpoint {

    /**
     * get remote address.
     *
     * @return remote address.
     */
    // 获得远程地址
    InetSocketAddress getRemoteAddress();

    /**
     * is connected.
     *
     * @return connected
     */
    // 判断通道是否连接
    boolean isConnected();

    /**
     * has attribute.
     *
     * @param key key.
     * @return has or has not.
     */
    // 判断是否有该key的值
    boolean hasAttribute(String key);

    /**
     * get attribute.
     *
     * @param key key.
     * @return value.
     */
    // 获得该key对应的值
    Object getAttribute(String key);

    /**
     * set attribute.
     *
     * @param key   key.
     * @param value value.
     */
    // 添加属性
    void setAttribute(String key, Object value);

    /**
     * remove attribute.
     *
     * @param key key.
     */
    // 移除属性
    void removeAttribute(String key);
}