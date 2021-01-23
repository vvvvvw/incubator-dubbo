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
package org.apache.dubbo.config.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Parameter
 */
/////提取config中标注了 Parameter注解的方法，并获取属性值到 parameters上，属性名以 prefix开头
// (注意提取 Parameter时 不只是包含加了@Parameter注解的属性，而是只要是配置类的get方法对应的属性且get方法返回值不是空字符串的都会添加到 parameters中 )
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Parameter {

    //参数对应的属性，如果为空，则使用get方法来提取
    String key() default "";

    //是否必须(如果为true，添加了@Parameter注解的get方法返回值不能为null)
    boolean required() default false;

    //是否跳过本属性
    boolean excluded() default false;

    //是否需要进行url编码
    boolean escaped() default false;

    //是否是attribute(有其他的方法 可以只收集 attribute 属性和对应的value，
    // 此时没有使用到required、excluded 、escaped、append、useKeyAsProperty这些属性)
    boolean attribute() default false;

    //是否需要从传入的parameters参数中提取原有的 key和default.key 的属性 并设置到 新的属性上去
    //(如果调用的时候指定了 前缀，则新增的属性名为 {前缀}.{key}属性；否则属性名还是 {key})
    boolean append() default false;

    /**
     * if {@link #key()} is specified, it will be used as the key for the annotated property when generating url.
     * by default, this key will also be used to retrieve the config value:
     * <pre>
     * {@code
     *  class ExampleConfig {
     *      // Dubbo will try to get "dubbo.example.alias_for_item=xxx" from .properties, if you want to use the original property
     *      // "dubbo.example.item=xxx", you need to set useKeyAsProperty=false.
     *      @Parameter(key = "alias_for_item")
     *      public getItem();
     *  }
     * }
     *
     * </pre>
     */
    //是否使用key作为 属性名，如果为false，则使用通过get或者set方法计算得到的字段名 作为属性名
    boolean useKeyAsProperty() default true;

}