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

import java.util.concurrent.atomic.LongAdder;

/**
 * Judge whether a particular invocation of service provider method should be allowed within a configured time interval.
 * As a state it contain name of key ( e.g. method), last invocation time, interval and rate count.
 */
//统计的数据结构 不精确
//可以看到该类中记录了一些访问的流量，并且设置了周期重置机制。
class StatItem {

    //服务名
    private String name;

    //最后一次重置的时间
    private long lastResetTime;

    //周期
    private long interval;

    //剩余多少流量
    private LongAdder token;

    //限制的流量大小
    private int rate;

    StatItem(String name, int rate, long interval) {
        this.name = name;
        this.rate = rate;
        this.interval = interval;
        this.lastResetTime = System.currentTimeMillis();
        this.token = buildLongAdder(rate);
    }

    public boolean isAllowable() {
        // 如果当前时间 大于最后一次时间加上周期，则重置
        long now = System.currentTimeMillis();
        if (now > lastResetTime + interval) {
            token = buildLongAdder(rate);
            lastResetTime = now;
        }

        //如果流量耗尽
        if (token.sum() < 0) {
            return false;
        }
        //否则，-1
        token.decrement();
        return true;
    }

    public long getInterval() {
        return interval;
    }


    public int getRate() {
        return rate;
    }


    long getLastResetTime() {
        return lastResetTime;
    }

    long getToken() {
        return token.sum();
    }

    @Override
    public String toString() {
        return new StringBuilder(32).append("StatItem ")
                .append("[name=").append(name).append(", ")
                .append("rate = ").append(rate).append(", ")
                .append("interval = ").append(interval).append("]")
                .toString();
    }

    private LongAdder buildLongAdder(int rate) {
        LongAdder adder = new LongAdder();
        adder.add(rate);
        return adder;
    }

}
