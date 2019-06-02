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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * random load balance.
 */
/*
该类是基于权重随机算法的负载均衡实现类，
我们先来讲讲原理，比如我有有一组服务器 servers = [A, B, C]，
他们他们对应的权重为 weights = [6, 3, 1]，权重总和为10，
现在把这些权重值平铺在一维坐标值上，分别出现三个区域，
A区域为[0,6)，B区域为[6,9)，C区域为[9,10)，
然后产生一个[0, 10)的随机数，看该数字落在哪个区间内，
就用哪台服务器，这样权重越大的，被击中的概率就越大。
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        // 获得服务长度
        int length = invokers.size();
        // Every invoker has the same weight?
        // 是否有相同的权重
        boolean sameWeight = true;
        // the weight of every invokers
        int[] weights = new int[length];
        // the first invoker's weight
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;
        // The sum of weights
        // 总的权重
        int totalWeight = firstWeight;
        // 遍历每个服务，计算相应权重
        for (int i = 1; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use
            weights[i] = weight;
            // Sum
            // 计算总的权重值
            totalWeight += weight;
            // 如果和第一个不相等则sameWeight为false
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }
        // 如果每个服务权重都不同，并且总的权重值不为0
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 循环让 offset 数减去服务提供者权重值，当 offset 小于0时，返回相应的 Invoker。
            // 举例说明一下，我们有 servers = [A, B, C]，weights = [6, 3, 1]，offset = 7。
            // 第一次循环，offset - 6 = 1 > 0，即 offset > 6，
            // 表明其不会落在服务器 A 对应的区间上。
            // 第二次循环，offset - 3 = -2 < 0，即 6 < offset < 9，
            // 表明其会落在服务器 B 对应的区间上
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // 如果所有服务提供者权重值相同，此时直接随机返回一个即可
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}
