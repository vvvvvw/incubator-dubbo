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
package org.apache.dubbo.common.utils;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CompatibleTypeUtils {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private CompatibleTypeUtils() {
    }

    /**
     * Compatible type convert. Null value is allowed to pass in. If no conversion is needed, then the original value
     * will be returned.
     * 兼容类型转换。 允许传递空值。如果不需要转换，则将返回原始值。
     * 兼容类型转换如下：
     *  String -> char, enum, Date,BigInteger,BigDecimal
     *  byte, short, int, long -> byte, short, int, long
     *  float, double -> float, double
     * <p>
     * Supported compatible type conversions include (primary types and corresponding wrappers are not listed):
     * <ul>
     * <li> String -> char, enum, Date（yyyy-MM-dd HH:mm:ss格式），Class,char[],byte, short, int, long,Boolean,float, double,,BigInteger,BigDecimal
     * <li> byte, short, int, long,float, double,BigInteger,BigDecimal -> byte, short, int, long,float, double,,BigInteger,BigDecimal,Date（时间戳）
     * <li> Collection -> array，Collection
     * </ul>
     */
    // null或者type为null 或者 value的类型是type的子类，直接返回 value
    // value为string，可以转换为：
    // char或者Character（如果value长度为1）、枚举类型、Class、char[]、BigInteger、BigDecimal、Short、Integer、Long、Double、Float、Byte、Boolean或 其包装类
    // Date、java.sql.Date、java.sql.Timestamp、java.sql.Time 使用 yyyy-MM-dd HH:mm:ss 格式转换为对应时间类型
    // value是 Number类型，转换为:
    // byte、short、int、long、double、double及其包装类、BigInteger、BigDecimal、Date
    // value是 集合类型，可以转换为：
    // 数组类型 或者其他 可以实例化且有复制构造函数的 集合类型
    // value是 数组类型，可以转换为：
    // List(最终转换为ArrayList) 或者其他 可以实例化且有复制构造函数的 集合类型、Set(最终转换为HashSet)
    // value是 数组类型，可以转换为：
    // List(最终转换为ArrayList) 或者其他 可以实例化且有复制构造函数的 集合类型、Set(最终转换为HashSet)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object compatibleTypeConvert(Object value, Class<?> type) {
        //null或者type为null 或者 value的类型是type的子类，直接返回 value
        if (value == null || type == null || type.isAssignableFrom(value.getClass())) {
            return value;
        }
        if (value instanceof String) {
            // value为string
            String string = (String) value;
            //如果value长度为1并且 type为 char或者Character，返回 value的第一个元素
            if (char.class.equals(type) || Character.class.equals(type)) {
                if (string.length() != 1) {
                    throw new IllegalArgumentException(String.format("CAN NOT convert String(%s) to char!" +
                            " when convert String to char, the String MUST only 1 char.", string));
                }
                return string.charAt(0);
            } else if (type.isEnum()) {
                // 转换为枚举类型
                return Enum.valueOf((Class<Enum>) type, string);
            } else if (type == BigInteger.class) {
                // 转换为 Class、char[]、BigInteger、BigDecimal、Short、Integer、Long、Double、Float、Byte、Boolean或 其包装类
                // Date、java.sql.Date、java.sql.Timestamp、java.sql.Time 使用 yyyy-MM-dd HH:mm:ss 格式转换为对应时间类型
                // 转换为Class 类型
                return new BigInteger(string);
            } else if (type == BigDecimal.class) {
                return new BigDecimal(string);
            } else if (type == Short.class || type == short.class) {
                return new Short(string);
            } else if (type == Integer.class || type == int.class) {
                return new Integer(string);
            } else if (type == Long.class || type == long.class) {
                return new Long(string);
            } else if (type == Double.class || type == double.class) {
                return new Double(string);
            } else if (type == Float.class || type == float.class) {
                return new Float(string);
            } else if (type == Byte.class || type == byte.class) {
                return new Byte(string);
            } else if (type == Boolean.class || type == boolean.class) {
                return new Boolean(string);
            } else if (type == Date.class || type == java.sql.Date.class || type == java.sql.Timestamp.class || type == java.sql.Time.class) {
                try {
                    Date date = new SimpleDateFormat(DATE_FORMAT).parse((String) value);
                    if (type == java.sql.Date.class) {
                        return new java.sql.Date(date.getTime());
                    } else if (type == java.sql.Timestamp.class) {
                        return new java.sql.Timestamp(date.getTime());
                    } else if (type == java.sql.Time.class) {
                        return new java.sql.Time(date.getTime());
                    } else {
                        return date;
                    }
                } catch (ParseException e) {
                    throw new IllegalStateException("Failed to parse date " + value + " by format " + DATE_FORMAT + ", cause: " + e.getMessage(), e);
                }
            } else if (type == Class.class) {
                try {
                    return ReflectUtils.name2class((String) value);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            } else if (char[].class.equals(type)) {
                // Process string to char array for generic invoke
                // See
                // - https://github.com/apache/incubator-dubbo/issues/2003
                int len = string.length();
                char[] chars = new char[len];
                string.getChars(0, len, chars, 0);
                return chars;
            }
        } else if (value instanceof Number) {
            // value是 Number类型，转换为:
            //byte、short、int、long、double、double、BigInteger、BigDecimal、Date
            Number number = (Number) value;
            if (type == byte.class || type == Byte.class) {
                return number.byteValue();
            } else if (type == short.class || type == Short.class) {
                return number.shortValue();
            } else if (type == int.class || type == Integer.class) {
                return number.intValue();
            } else if (type == long.class || type == Long.class) {
                return number.longValue();
            } else if (type == float.class || type == Float.class) {
                return number.floatValue();
            } else if (type == double.class || type == Double.class) {
                return number.doubleValue();
            } else if (type == BigInteger.class) {
                return BigInteger.valueOf(number.longValue());
            } else if (type == BigDecimal.class) {
                return BigDecimal.valueOf(number.doubleValue());
            } else if (type == Date.class) {
                return new Date(number.longValue());
            }
        } else if (value instanceof Collection) {
            //value是 集合类型，可以转换为：
            //数组类型 或者其他 可以实例化且有复制构造函数的 集合类型
            Collection collection = (Collection) value;
            if (type.isArray()) {
                int length = collection.size();
                Object array = Array.newInstance(type.getComponentType(), length);
                int i = 0;
                for (Object item : collection) {
                    Array.set(array, i++, item);
                }
                return array;
            } else if (!type.isInterface()) {
                try {
                    Collection result = (Collection) type.newInstance();
                    result.addAll(collection);
                    return result;
                } catch (Throwable e) {
                }
            } else if (type == List.class) {
                return new ArrayList<Object>(collection);
            } else if (type == Set.class) {
                return new HashSet<Object>(collection);
            }
        } else if (value.getClass().isArray() && Collection.class.isAssignableFrom(type)) {
            // value是 数组类型，可以转换为：
            // ArrayList 或者其他 可以实例化且有复制构造函数的 集合类型、HashSet
            Collection collection;
            if (!type.isInterface()) {
                try {
                    collection = (Collection) type.newInstance();
                } catch (Throwable e) {
                    collection = new ArrayList<Object>();
                }
            } else if (type == Set.class) {
                collection = new HashSet<Object>();
            } else {
                collection = new ArrayList<Object>();
            }
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collection.add(Array.get(value, i));
            }
            return collection;
        }
        return value;
    }
}