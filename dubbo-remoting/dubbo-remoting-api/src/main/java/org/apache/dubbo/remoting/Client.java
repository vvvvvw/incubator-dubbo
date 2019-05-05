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

import org.apache.dubbo.common.Resetable;

/**
 * Remoting Client. (API/SPI, Prototype, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 *
 * @see org.apache.dubbo.remoting.Transporter#connect(org.apache.dubbo.common.URL, ChannelHandler)
 */
//继承了Endpoint、Channel和Resetable接口
//继承Endpoint原因:
//客户端，在传输层，其实Client和Server的区别只是在语义上区别，
// 并不区分请求和应答职责，在交换层客户端和服务端也是一个点，
// 但是已经是有方向的点，所以区分了明确的请求和应答职责。
// 两者都具备发送的能力，只是客户端和服务端所关注的事情不一样，
//继承Channel原因:
// 继承Channel是因为客户端跟通道是一一对应的，所以做了这样的设计
public interface Client extends Endpoint, Channel, Resetable, IdleSensible {

    /**
     * reconnect.
     */
    // 重连
    void reconnect() throws RemotingException;

    // 重置，不推荐使用
    @Deprecated
    void reset(org.apache.dubbo.common.Parameters parameters);

}
