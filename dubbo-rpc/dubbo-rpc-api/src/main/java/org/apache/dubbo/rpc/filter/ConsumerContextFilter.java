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
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;

/**
 * ConsumerContextFilter set current RpcContext with invoker,invocation, local host, remote host and port
 * for consumer invoker.It does it to make the requires info available to execution thread's RpcContext.
 *
 * @see org.apache.dubbo.rpc.Filter
 * @see RpcContext
 */
//该过滤器做的是在当前的RpcContext中记录本地调用的一次状态信息。
@Activate(group = Constants.CONSUMER, order = -10000)
public class ConsumerContextFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // // 获得上下文，设置invoker，会话域，本地地址和原创地址
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
                //如果传入的端口为0，在使用该地址绑定时，操作系统会选择一个随机端口
                .setLocalAddress(NetUtils.getLocalHost(), 0)
                .setRemoteAddress(invoker.getUrl().getHost(),
                        invoker.getUrl().getPort());
        // 如果该会话域是rpc会话域
        if (invocation instanceof RpcInvocation) {
            // 设置实体域
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        try {
            // todo 为什么要清理服务端上下文？
            // TODO should we clear server context?
            RpcContext.removeServerContext();
            return invoker.invoke(invocation);
        } finally {
            // todo 为什么要清理attachment？
            // TODO removeContext? but we need to save future for RpcContext.getFuture() API. If clear attachments here, attachments will not available when postProcessResult is invoked.
            RpcContext.getContext().clearAttachments();
        }
    }

    @Override
    public Result onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        // 将响应上下文中的attachment设置到ServerContext中
        RpcContext.getServerContext().setAttachments(result.getAttachments());
        return result;
    }
}
