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
package org.apache.dubbo.common.extension.support;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.utils.ArrayUtils;

import java.util.Arrays;
import java.util.Comparator;

/**
 * OrderComparator
 */
//该类在ExtensionLoader类的getActivateExtension方法中被运用到，作为自动激活拓展对象的排序器。
public class ActivateComparator implements Comparator<Object> {

    public static final Comparator<Object> COMPARATOR = new ActivateComparator();

    @Override
    public int compare(Object o1, Object o2) {
        //基本排序
        if (o1 == null && o2 == null) {
            return 0;
        }
        if (o1 == null) {
            return -1;
        }
        if (o2 == null) {
            return 1;
        }
        if (o1.equals(o2)) {
            return 0;
        }

        //递归找到 o1 实现的添加了@SPI注解的接口
        Class<?> inf = findSpi(o1.getClass());

        ActivateInfo a1 = parseActivate(o1.getClass());
        ActivateInfo a2 = parseActivate(o2.getClass());

        if ((a1.applicableToCompare() || a2.applicableToCompare()) && inf != null) {
            //根据接口类型获取 a2扩展名
            ExtensionLoader<?> extensionLoader = ExtensionLoader.getExtensionLoader(inf);
            if (a1.applicableToCompare()) {
                String n2 = extensionLoader.getExtensionName(o2.getClass());
                if (a1.isLess(n2)) {
                    return -1;
                }

                if (a1.isMore(n2)) {
                    return 1;
                }
            }

            if (a2.applicableToCompare()) {
                String n1 = extensionLoader.getExtensionName(o1.getClass());
                if (a2.isLess(n1)) {
                    return 1;
                }

                if (a2.isMore(n1)) {
                    return -1;
                }
            }
        }
        //返回 o1.order > o2.order
        int n1 = a1 == null ? 0 : a1.order;
        int n2 = a2 == null ? 0 : a2.order;
        // never return 0 even if n1 equals n2, otherwise, o1 and o2 will override each other in collection like HashSet
        return n1 > n2 ? 1 : -1;
    }

    //递归寻找本类实现的添加了@SPI注解的接口
    private Class<?> findSpi(Class clazz) {
        if (clazz.getInterfaces().length <= 0) {
            return null;
        }

        for (Class<?> intf : clazz.getInterfaces()) {
            if (intf.isAnnotationPresent(SPI.class)) {
                return intf;
            } else {
                Class result = findSpi(intf);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }


    private ActivateInfo parseActivate(Class<?> clazz) {
        ActivateInfo info = new ActivateInfo();
        //使用Activate注解的 `after` 和 `before` 属性，排序
        if (clazz.isAnnotationPresent(Activate.class)) {
            Activate activate = clazz.getAnnotation(Activate.class);
            info.before = activate.before();
            info.after = activate.after();
            info.order = activate.order();
        } else {
            com.alibaba.dubbo.common.extension.Activate activate = clazz.getAnnotation(
                    com.alibaba.dubbo.common.extension.Activate.class);
            info.before = activate.before();
            info.after = activate.after();
            info.order = activate.order();
        }
        return info;
    }

    private static class ActivateInfo {
        private String[] before;
        private String[] after;
        private int order;


        //before或者after是否为空
        private boolean applicableToCompare() {
            return ArrayUtils.isNotEmpty(before) || ArrayUtils.isNotEmpty(after);
        }

        //本扩展在name对应的扩展 之前
        private boolean isLess(String name) {
            return Arrays.asList(before).contains(name);
        }

        //本扩展在name对应的对象 之后
        private boolean isMore(String name) {
            return Arrays.asList(after).contains(name);
        }
    }
}
