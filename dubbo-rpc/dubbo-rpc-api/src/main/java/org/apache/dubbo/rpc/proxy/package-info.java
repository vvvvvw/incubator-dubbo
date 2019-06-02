/**
 * ymm56.com Inc.
 * Copyright (c) 2013-2019 All Rights Reserved.
 */
package org.apache.dubbo.rpc.proxy;

//实现了代理的逻辑。
/*
dubbo对于动态代理有两种方法实现，分别是javassist和jdk。Proxy 层封装了所有接口的透明化代理，
而在其它层都以 Invoker 为中心，只有到了暴露给用户使用时，才用 Proxy 将 Invoker 转成接口，
或将接口实现转成 Invoker，也就是去掉 Proxy 层 RPC 是可以 Run 的，
只是不那么透明，不那么看起来像调本地服务一样调远程服务。
 */