/**
 * ymm56.com Inc.
 * Copyright (c) 2013-2019 All Rights Reserved.
 */
package org.apache.dubbo.remoting.exchange;

/*
信息交换层。官方文档对这一层的解释是封装请求响应模式，同步转异步，
以 Request, Response为中心，
扩展接口为 Exchanger, ExchangeChannel, ExchangeClient, ExchangeServer。
它应该算是在信息传输层上又做了部分装饰，为了适应rpc调用的一些需求，
比如rpc调用中一次请求只关心它所对应的响应，这个时候只是一个message消息传输过来，
是无法区分这是新的请求还是上一个请求的响应，这种类似于幂等性的问题以及rpc异步处理返回结果、
内置事件等特性都是在Transport层无法解决满足的，所有在Exchange层讲message
分成了request和response两种类型，并且在这两个模型上增加一些系统字段来处理问题。
具体我会在下面讲到。而dubbo把一条消息分为了协议头和内容两部分：协议头包括系统字段，例如编号等，
内容包括具体请求的参数和响应的结果等。在exchange层中大量逻辑都是基于协议头的。

这一层区分出了请求和响应的概念，设计了Request和Response模型，整个信息交换都围绕这两大模型，并且设计了dubbo协议，解决拆包粘包问题，
在信息交换中协议头携带的信息起到了关键作用，也满足了rpc调用的一些需求。
 */