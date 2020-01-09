/**
 * ymm56.com Inc.
 * Copyright (c) 2013-2019 All Rights Reserved.
 */
package org.apache.dubbo.common.serialize;

/*
该模块中封装了各类序列化框架的支持实现。

序列化就是将对象转成字节流，用于网络传输，以及将字节流转为对象，用于在收到字节流数据后还原成对象。序列化的好处我就不多说了，无非就是安全性更好、可跨平台等。网上有很多总结的很好，我在这里主要讲讲dubbo中序列化的设计和实现了哪些序列化方式。

dubbo在2.6.x版本中，支持五种序列化方式，分别是

fastjson：依赖阿里的fastjson库，功能强大(支持普通JDK类包括任意Java Bean Class、Collection、Map、Date或enum)
fst：完全兼容JDK序列化协议的系列化框架，序列化速度大概是JDK的4-10倍，大小是JDK大小的1/3左右。
hessian2：hessian是一种跨语言的高效二进制序列化方式。但这里实际不是原生的hessian2序列化，而是阿里修改过的hessian lite，它是dubbo RPC默认启用的序列化方式
jdk：JDK自带的Java序列化实现。
kryo：是一个快速序列化/反序列化工具，其使用了字节码生成机制（底层依赖了 ASM 库），因此具有比较好的运行速度，速度快，序列化后体积小，跨语言支持较复杂
 */