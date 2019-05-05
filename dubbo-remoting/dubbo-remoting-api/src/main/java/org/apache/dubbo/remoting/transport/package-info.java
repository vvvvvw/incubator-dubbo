/**
 * ymm56.com Inc.
 * Copyright (c) 2013-2019 All Rights Reserved.
 */
package org.apache.dubbo.remoting.transport;

/*
Transport层也就是网络传输层，在远程通信中必然会涉及到传输。它在dubbo 的
框架设计中也处于倒数第二层，当然最底层是序列化，这个后面介绍。官方文档
对Transport层的解释是抽象 mina 和 netty 为统一接口，
以 Message 为中心，扩展接口为 Channel、Transporter、Client、Server、Codec。
 */