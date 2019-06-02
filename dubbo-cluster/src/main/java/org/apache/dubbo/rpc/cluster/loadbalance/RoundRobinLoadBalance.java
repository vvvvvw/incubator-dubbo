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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";

    //回收间隔,会移除 当前时间-最新更新时间>RECYCLE_PERIOD的WeightedRoundRobin
    private static final int RECYCLE_PERIOD = 60000;

    //该内部类是一个加权轮询器，它记录了某一个服务提供者的一些数据，
    // 比如权重、比如当前已经有多少请求落在该服务提供者上等。
    protected static class WeightedRoundRobin {
        //权重
        private int weight;
        //当前已经有多少请求落在该服务提供者身上，也可以看成是一个动态的权重
        private AtomicLong current = new AtomicLong(0);
        //最后一次更新时间
        private long lastUpdate;
        public int getWeight() {
            return weight;
        }
        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }
        public long increaseCurrent() {
            return current.addAndGet(weight);
        }
        //减少流量
        public void sel(int total) {
            current.addAndGet(-1 * total);
        }
        public long getLastUpdate() {
            return lastUpdate;
        }
        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    //Map<{group}/{interfaceName}:{version}.{方法名},Map<invoker.getUrl().toIdentityString(),WeightedRoundRobin>>
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();
    private AtomicBoolean updateLock = new AtomicBoolean();
    
    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }

    //关键是一些数据记录，在每次请求都会记录落在该服务上的请求数，然后在根据权重来分配，
    // 并且会有回收时间来处理一些长时间未被更新的节点。
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // key = {group}/{interfaceName}:{version}.{方法名}
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }
        // 权重总和
        int totalWeight = 0;
        // 最小权重
        long maxCurrent = Long.MIN_VALUE;
        // 获得现在的时间戳
        long now = System.currentTimeMillis();
        // 创建已经选择的invoker
        Invoker<T> selectedInvoker = null;
        // 创建加权轮询器
        WeightedRoundRobin selectedWRR = null;
        // 下面这个循环主要做了这样几件事情：
        //   1. 遍历 Invoker 列表，检测当前 Invoker 是否有
        //      相应的 WeightedRoundRobin，没有则创建
        //   2. 检测 Invoker 权重是否发生了变化，若变化了，
        //      则更新 WeightedRoundRobin 的 weight 字段
        //   3. 让 current 字段加上自身权重，等价于 current += weight
        //   4. 设置 lastUpdate 字段，即 lastUpdate = now
        //   5. 寻找具有最大 current 的 Invoker，以及 Invoker 对应的 WeightedRoundRobin，
        //      暂存起来，留作后用
        //   6. 计算权重总和
        for (Invoker<T> invoker : invokers) {
            // 获得 invoker.getUrl()的identify的值
            String identifyString = invoker.getUrl().toIdentityString();
            // 获得加权轮询器
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            // 计算权重
            int weight = getWeight(invoker, invocation);

            // 如果加权轮询器为空
            if (weightedRoundRobin == null) {
                // 创建加权轮询器
                weightedRoundRobin = new WeightedRoundRobin();
                // 设置权重
                weightedRoundRobin.setWeight(weight);
                // 加入集合
                map.putIfAbsent(identifyString, weightedRoundRobin);
            }
            // 如果权重跟之前的权重不一样，则重新设置权重
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            // 计数器加 weight
            long cur = weightedRoundRobin.increaseCurrent();
            // 更新最后一次更新时间
            weightedRoundRobin.setLastUpdate(now);
            // 当落在该服务提供者的统计数大于已经获取到的最大的流量，更新
            if (cur > maxCurrent) {
                // 赋值
                maxCurrent = cur;
                // 被选择的selectedInvoker赋值
                selectedInvoker = invoker;
                // 被选择的加权轮询器赋值
                selectedWRR = weightedRoundRobin;
            }
            // 累加
            totalWeight += weight;
        }
        // 如果更新锁不能获得并且invokers的大小跟map大小不匹配
        // 对 <identifyString, WeightedRoundRobin> 进行检查，过滤掉长时间未被更新的节点。
        // 该节点可能挂了，invokers 中不包含该节点，所以该节点的 lastUpdate 长时间无法被更新。
        // 若未更新时长超过阈值后，就会被移除掉，默认阈值为60秒。
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<String, WeightedRoundRobin>();
                    // 复制
                    newMap.putAll(map);
                    // 轮询
                    Iterator<Entry<String, WeightedRoundRobin>> it = newMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, WeightedRoundRobin> item = it.next();
                        // 如果大于回收时间，则进行回收
                        if (now - item.getValue().getLastUpdate() > RECYCLE_PERIOD) {
                            // 从集合中移除
                            it.remove();
                        }
                    }
                    // 加入集合
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }
        // 如果被选择的selectedInvoker不为空
        if (selectedInvoker != null) {
            // 设置总的权重
            selectedWRR.sel(totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
