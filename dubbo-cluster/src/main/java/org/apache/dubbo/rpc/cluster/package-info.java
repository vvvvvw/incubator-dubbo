/**
 * ymm56.com Inc.
 * Copyright (c) 2013-2019 All Rights Reserved.
 */
package org.apache.dubbo.rpc.cluster;

/*
Directory：Directory可以看成是多个Invoker的集合，但是它的值会随着注册中心中服务变化推送而动态变化，那么Invoker以及如何动态变化就是一个重点内容。
集群容错：Cluster 将 Directory 中的多个 Invoker 伪装成一个 Invoker，对上层透明，伪装过程包含了容错逻辑，调用失败后，重试另一个。
路由：dubbo路由规则，路由规则决定了一次dubbo服务调用的目标服务器，路由规则分两种：条件路由规则和脚本路由规则，并且支持可拓展。
负载均衡策略：dubbo支持的所有负载均衡策略算法。
配置：根据url上的配置规则生成配置信息
分组聚合：合并返回结果。
本地伪装：mork通常用于服务降级，mock只在出现非业务异常(比如超时，网络异常等)时执行


集群工作过程可分为两个阶段，第一个阶段是在服务消费者初始化期间，
集群 Cluster 实现类为服务消费者创建 Cluster Invoker 实例，即上图中的 merge 操作。
第二个阶段是在服务消费者进行远程调用时。以 FailoverClusterInvoker 为例，
该类型 Cluster Invoker 首先会调用 Directory 的 list 方法列举 Invoker 列表
（可将 Invoker 简单理解为服务提供者）。Directory 的用途是保存 Invoker，
可简单类比为 List<invoker>。其实现类 RegistryDirectory 是一个动态服务目录，
可感知注册中心配置的变化，它所持有的 Inovker 列表会随着注册中心内容的变化而变化。
每次变化后，RegistryDirectory 会动态增删 Inovker，并调用 Router 的 route 方法进行路由，
过滤掉不符合路由规则的 Invoker。当 FailoverClusterInvoker 拿到 Directory 返回的 Invoker 列表后，
它会通过 LoadBalance 从 Invoker 列表中选择一个 Inovker。最后 FailoverClusterInvoker 会将
参数传给 LoadBalance 选择出的 Invoker 实例的 invoker 方法，进行真正的远程调用。
 */