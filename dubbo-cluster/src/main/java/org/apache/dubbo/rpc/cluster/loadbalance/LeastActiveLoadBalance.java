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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 */
/*
该负载均衡策略基于最少活跃调用数算法，某个服务活跃调用数越小，
表明该服务提供者效率越高，也就表明单位时间内能够处理的请求更多。
此时应该选择该类服务器。实现很简单，就是每一个服务都有一个活跃数
active来记录该服务的活跃值，每收到一个请求，该active就会加1，
每完成一个请求，active就会减1。在服务运行一段时间后，
性能好的服务提供者处理请求的速度更快，因此活跃数下降的也越快，
此时这样的服务提供者能够优先获取到新的服务请求。除了最小活跃数，
还引入了权重值，也就是当活跃数一样的时候，
选择利用权重法来进行选择，如果权重也一样，那么随机选择一个。
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        // 获得服务长度
        int length = invokers.size();
        // 最小的活跃数
        // The least active value of all invokers
        int leastActive = -1;
        // 具有相同“最小活跃数”的服务者提供者（以下用 Invoker 代称）数量
        // The number of invokers having the same least active value (leastActive)
        int leastCount = 0;
        // leastIndexs 用于记录具有相同“最小活跃数”的 Invoker 在 invokers 列表中的下标信息
        // The index of invokers having the same least active value (leastActive)
        int[] leastIndexes = new int[length];
        // the weight of every invokers
        int[] weights = new int[length];
        // 所有具有最小活跃数的节点的总权重
        // The sum of the warmup weights of all the least active invokes
        int totalWeight = 0;
        // 第一个最小活跃数的 Invoker 权重值，用于与其他具有相同最小活跃数的 Invoker 的权重进行对比，
        // 以检测是否“所有具有相同最小活跃数的 Invoker 的权重”均相等
        // The weight of the first least active invoke
        int firstWeight = 0;
        // 是否所有具有最小活跃数的节点权重相同
        // Every least active invoker has the same weight value?
        boolean sameWeight = true;


        // Filter out all the least active invokers
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // 获取 Invoker 对应的活跃数
            // Get the active number of the invoke
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // 获得该服务的权重
            // Get the weight of the invoke configuration. The default value is 100.
            int afterWarmup = getWeight(invoker, invocation);
            // 存储权重
            // save for later use
            weights[i] = afterWarmup;
            // If it is the first invoker or the active number of the invoker is less than the current least active number
            //如果遇到第一个 具有最小活跃数 的节点
            if (leastActive == -1 || active < leastActive) {
                // 记录当前最小的活跃数
                // Reset the active number of the current invoker to the least active number
                leastActive = active;
                // 更新 leastCount 为 1
                // Reset the number of least active invokers
                leastCount = 1;
                // 记录当前下标值到 leastIndexs 中
                // Put the first least active invoker first in leastIndexs
                leastIndexes[0] = i;
                //重置总权重
                // Reset totalWeight
                totalWeight = afterWarmup;
                // Record the weight the first least active invoker
                firstWeight = afterWarmup;
                // Each invoke has the same weight (only one invoker here)
                sameWeight = true;
                // If current invoker's active value equals with leaseActive, then accumulating.
                // 如果当前 Invoker 的活跃数 active 与最小活跃数 leastActive 相同
            } else if (active == leastActive) {
                // 在 leastIndexs 中记录下当前 Invoker 在 invokers 集合中的下标
                // Record the index of the least active invoker in leastIndexs order
                leastIndexes[leastCount++] = i;
                // 累加权重
                // Accumulate the total weight of the least active invoker
                totalWeight += afterWarmup;
                // If every invoker has the same weight?
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // 当只有一个 Invoker 具有最小活跃数，此时直接返回该 Invoker 即可
        // Choose an invoker from all the least active invokers
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexes[0]);
        }
        // 有多个 Invoker 具有相同的最小活跃数，但它们之间的权重不同
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on 
            // totalWeight.
            // 随机生成一个数字
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            //根据权重随机选择一个节点 ( // 相关算法可以参考RandomLoadBalance)
            // Return a invoker based on the random value.
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
        // 如果权重一样，则随机取一个
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}