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

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.cluster.Merger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MergerFactory {

    //Merger 对象缓存 Map<Merger对应的第一个泛型参数，Merger>
    private static final ConcurrentMap<Class<?>, Merger<?>> mergerCache =
            new ConcurrentHashMap<Class<?>, Merger<?>>();

    /**
     * Find the merger according to the returnType class, the merger will
     * merge an array of returnType into one
     *
     * @param returnType the merger will return this type
     * @return the merger which merges an array of returnType into one, return null if not exist
     * @throws IllegalArgumentException if returnType is null
     */
    //获得指定类型的Merger对象
    public static <T> Merger<T> getMerger(Class<T> returnType) {
        if (returnType == null) {
            throw new IllegalArgumentException("returnType is null");
        }

        Merger result;
        // 如果类型是数组，则取得数组中元素类型的merger
        if (returnType.isArray()) {
            // 获得类型
            Class type = returnType.getComponentType();
            // 从缓存中获得该类型的Merger对象
            result = mergerCache.get(type);
            if (result == null) {
                // 如果为空，则加载Merger
                // 初始化所有的 Merger 扩展对象，到 mergerCache 缓存中。
                loadMergers();
                // 从集合中取出对应的Merger对象
                result = mergerCache.get(type);
            }
            // 如果没有对应的merge并且type的类型也不是原生类型，则使用ArrayMerger
            if (result == null && !type.isPrimitive()) {
                result = ArrayMerger.INSTANCE;
            }
        } else {
            // 否则直接从mergerCache中取出
            result = mergerCache.get(returnType);
            if (result == null) {
                // 初始化所有的 Merger 扩展对象，到 mergerCache 缓存中。
                loadMergers();
                // 从集合中取出
                result = mergerCache.get(returnType);
            }
        }
        return result;
    }

    /**
     * 初始化所有的 Merger 扩展对象，到 mergerCache 缓存中。
     */
    static void loadMergers() {
        // 获得Merger所有的扩展对象名
        Set<String> names = ExtensionLoader.getExtensionLoader(Merger.class)
                .getSupportedExtensions();
        // 遍历
        for (String name : names) {
            // 加载每一个扩展实现，然后放入缓存。
            Merger m = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(name);
            mergerCache.putIfAbsent(ReflectUtils.getGenericClass(m.getClass()), m);
        }
    }

}
