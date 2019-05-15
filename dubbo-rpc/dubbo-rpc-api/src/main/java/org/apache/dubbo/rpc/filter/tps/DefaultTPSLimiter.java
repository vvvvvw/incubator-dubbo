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
package org.apache.dubbo.rpc.filter.tps;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * DefaultTPSLimiter is a default implementation for tps filter. It is an in memory based implementation for storing
 * tps information. It internally use
 *
 * @see org.apache.dubbo.rpc.filter.TpsLimitFilter
 */
//该类实现了TPSLimiter，是默认的tps限流器实现。
public class DefaultTPSLimiter implements TPSLimiter {

    //统计项集合
    private final ConcurrentMap<String, StatItem> stats = new ConcurrentHashMap<String, StatItem>();

    @Override
    public boolean isAllowable(URL url, Invocation invocation) {
        // 获得tps限制大小，默认-1，不限制
        int rate = url.getParameter(Constants.TPS_LIMIT_RATE_KEY, -1);
        //默认1分钟作为间隔
        // 获得限流周期
        long interval = url.getParameter(Constants.TPS_LIMIT_INTERVAL_KEY, Constants.DEFAULT_TPS_LIMIT_INTERVAL);
        String serviceKey = url.getServiceKey();
        // 如果限制
        if (rate > 0) {
            // 从集合中获得统计项
            StatItem statItem = stats.get(serviceKey);
            if (statItem == null) {
                // 如果为空，则新建
                stats.putIfAbsent(serviceKey, new StatItem(serviceKey, rate, interval));
                statItem = stats.get(serviceKey);
            } else {
                //如果 rate或者时间间隔修改了，重建
                //rate or interval has changed, rebuild
                if (statItem.getRate() != rate || statItem.getInterval() != interval) {
                    stats.put(serviceKey, new StatItem(serviceKey, rate, interval));
                    statItem = stats.get(serviceKey);
                }
            }
            // 返回是否允许
            return statItem.isAllowable();
        } else {
            //如果不限制，移除该服务的统计项
            StatItem statItem = stats.get(serviceKey);
            if (statItem != null) {
                // 移除该服务的统计项
                stats.remove(serviceKey);
            }
        }

        return true;
    }

}
