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
package org.apache.dubbo.rpc.cluster.merger;

import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.rpc.cluster.Merger;

import java.lang.reflect.Array;

public class ArrayMerger implements Merger<Object[]> {

    //单例
    public static final ArrayMerger INSTANCE = new ArrayMerger();

    // TODO: 会过滤掉为null的元素  by 15258 2019/6/2 10:40
    //循环合并
    @Override
    public Object[] merge(Object[]... items) {
        // 如果长度为0  则直接返回
        if (ArrayUtils.isEmpty(items)) {
            return new Object[0];
        }

        int i = 0;
        // items中第一个不为null的元素
        while (i < items.length && items[i] == null) {
            i++;
        }

        //如果只有一个元素，直接返回
        if (i == items.length) {
            return new Object[0];
        }

        // 获得数组类型
        Class<?> type = items[i].getClass().getComponentType();

        // 总长
        int totalLen = 0;
        // 遍历所有需要合并的对象
        for (; i < items.length; i++) {
            if (items[i] == null) {
                continue;
            }
            Class<?> itemType = items[i].getClass().getComponentType();
            if (itemType != type) {
                throw new IllegalArgumentException("Arguments' types are different");
            }
            // 累加数组长度
            totalLen += items[i].length;
        }

        //如果元素为0，直接返回空数组
        if (totalLen == 0) {
            return new Object[0];
        }

        // 创建长度
        Object result = Array.newInstance(type, totalLen);

        int index = 0;
        // 遍历需要合并的对象
        for (Object[] array : items) {
            // 遍历每个数组中的数据
            if (array != null) {
                for (int j = 0; j < array.length; j++) {
                    // 加入到最终结果中
                    Array.set(result, index++, array[j]);
                }
            }
        }
        return (Object[]) result;
    }
}
