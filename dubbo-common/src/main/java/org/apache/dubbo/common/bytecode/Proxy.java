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
package org.apache.dubbo.common.bytecode;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.ReflectUtils;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy.
 */

public abstract class Proxy {
    public static final InvocationHandler RETURN_NULL_INVOKER = (proxy, method, args) -> null;
    public static final InvocationHandler THROW_UNSUPPORTED_INVOKER = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("Method [" + ReflectUtils.getName(method) + "] unimplemented.");
        }
    };
    private static final AtomicLong PROXY_CLASS_COUNTER = new AtomicLong(0);
    private static final String PACKAGE_NAME = Proxy.class.getPackage().getName();
    //Map<类加载器，Map<所有实现接口使用;连接，WeakReference<代理类>>>
    private static final Map<ClassLoader, Map<String, Object>> ProxyCacheMap = new WeakHashMap<ClassLoader, Map<String, Object>>();

    private static final Object PendingGenerationMarker = new Object();

    protected Proxy() {
    }

    /**
     * Get proxy.
     *
     * @param ics interface class array.
     * @return Proxy instance.
     */
    // 获得代理类
    public static Proxy getProxy(Class<?>... ics) {
        // 获得代理类
        return getProxy(ClassHelper.getClassLoader(Proxy.class), ics);
    }

    /**
     * Get proxy.
     *
     * @param cl  class loader.
     * @param ics interface class array.
     * @return Proxy instance.
     */
    /*
    遍历代理接口，获取接口的全限定名，并以分号分隔连接成字符串，以此字符串为key，查找缓存map，如果缓存存在，则获取代理对象直接返回。
由一个AtomicLong自增生成代理类类名后缀id，防止冲突
遍历接口中的方法，获取返回类型和参数类型，构建的方法体见注释
创建工具类ClassGenerator实例，添加静态字段Method[] methods，添加实例对象InvokerInvocationHandler hanler，添加参数为InvokerInvocationHandler的构造器，添加无参构造器，然后使用toClass方法生成对应的字节码。
4中生成的字节码对象为服务接口的代理对象，而Proxy类本身是抽象类，需要实现newInstance(InvocationHandler handler)方法，生成Proxy的实现类，其中proxy0即上面生成的服务接口的代理对象。
     */
    public static Proxy getProxy(ClassLoader cl, Class<?>... ics) {
        // 最大的代理接口数限制是65535
        if (ics.length > Constants.MAX_PROXY_COUNT) {
            throw new IllegalArgumentException("interface limit exceeded");
        }

        StringBuilder sb = new StringBuilder();
        // 遍历代理接口，获取接口的全限定名并以分号分隔连接成字符串
        for (int i = 0; i < ics.length; i++) {
            // 获得类名
            String itf = ics[i].getName();
            // 判断是否为接口
            if (!ics[i].isInterface()) {
                throw new RuntimeException(itf + " is not a interface.");
            }

            Class<?> tmp = null;
            try {
                // 获得与itf对应的Class对象
                tmp = Class.forName(itf, false, cl);
            } catch (ClassNotFoundException e) {
            }

            // 如果通过类名获得的类型跟ics中的类型不一样，则抛出异常
            if (tmp != ics[i]) {
                throw new IllegalArgumentException(ics[i] + " is not visible from class loader");
            }

            // 拼接类
            sb.append(itf).append(';');
        }

        // use interface class name list as key.
        String key = sb.toString();

        // get cache by class loader.
        Map<String, Object> cache;
        synchronized (ProxyCacheMap) {
            // 通过类加载器获得缓存
            cache = ProxyCacheMap.computeIfAbsent(cl, k -> new HashMap<>());
        }

        Proxy proxy = null;
        // TODO: >>> 如果生成速度比较慢或者消耗的资源比较多，先把一个pending对象放入缓存
        synchronized (cache) {
            do {
                Object value = cache.get(key);
                // 如果缓存中存在，则直接返回代理对象
                if (value instanceof Reference<?>) {
                    proxy = (Proxy) ((Reference<?>) value).get();
                    if (proxy != null) {
                        return proxy;
                    }
                }

                // 是等待生成的类型，则等待
                if (value == PendingGenerationMarker) {
                    try {
                        cache.wait();
                    } catch (InterruptedException e) {
                    }
                } else {
                    // 否则将pending对象放入缓存中
                    cache.put(key, PendingGenerationMarker);
                    break;
                }
            }
            while (true);
        }

        // AtomicLong自增生成代理类类名后缀id，防止冲突
        long id = PROXY_CLASS_COUNTER.getAndIncrement();
        String pkg = null;
        ClassGenerator ccp = null, ccm = null;
        try {
            ccp = ClassGenerator.newInstance(cl);

            //方法描述集合
            Set<String> worked = new HashSet<>();
            List<Method> methods = new ArrayList<>();

            for (int i = 0; i < ics.length; i++) {
                // 判断是否为public
                if (!Modifier.isPublic(ics[i].getModifiers())) {
                    // 获得该类的包名
                    String npkg = ics[i].getPackage().getName();
                    if (pkg == null) {
                        pkg = npkg;
                    } else {
                        // 是否 非public接口类来自不同包
                        if (!pkg.equals(npkg)) {
                            throw new IllegalArgumentException("non-public interfaces from different packages");
                        }
                    }
                }
                // 把接口加入到ccp的mInterfaces中
                ccp.addInterface(ics[i]);

                // 遍历每个类的方法
                for (Method method : ics[i].getMethods()) {
                    // 获得方法描述 这个方法描述是自定义：
                    // 例如：int do(int arg1) => "do(I)I"
                    // 例如：void do(String arg1,boolean arg2) => "do(Ljava/lang/String;Z)V"
                    String desc = ReflectUtils.getDesc(method);
                    if (worked.contains(desc)) {
                        continue;
                    }
                    // 如果集合中不存在，则加入该描述
                    worked.add(desc);

                    int ix = methods.size();
                    // 获得方法返回类型
                    Class<?> rt = method.getReturnType();
                    // 获得方法参数类型
                    Class<?>[] pts = method.getParameterTypes();

                    // 新建一句代码
                    // 例如Object[] args = new Object[参数数量】
                    StringBuilder code = new StringBuilder("Object[] args = new Object[").append(pts.length).append("];");
                    // 每一个参数都生成一句代码
                    // 例如args[0] = ($w)$1;
                    for (int j = 0; j < pts.length; j++) {
                        code.append(" args[").append(j).append("] = ($w)$").append(j + 1).append(";");
                    }
                    // 例如 Object ret = handler.invoke(this, methods[3], args); ix:第几个方法
                    code.append(" Object ret = handler.invoke(this, methods[").append(ix).append("], args);");
                    // 如果方法不是void类型
                    // 则拼接 return ret;
                    if (!Void.TYPE.equals(rt)) {
                        code.append(" return ").append(asArgument(rt, "ret")).append(";");
                    }

                    methods.add(method);
                    ccp.addMethod(method.getName(), method.getModifiers(), rt, pts, method.getExceptionTypes(), code.toString());
                }
            }

            //如果都是public接口，生成的 类的包名为 Proxy的包名，类名为 proxy+序号
            //如果有非public接口，生成的类的包名 为f非 public接口的包名，类名为 proxy+序号
            if (pkg == null) {
                pkg = PACKAGE_NAME;
            }

            //生成代理类
            // create ProxyInstance class.
            String pcn = pkg + ".proxy" + id;
            ccp.setClassName(pcn);
            // 添加静态字段Method[] methods
            ccp.addField("public static java.lang.reflect.Method[] methods;");
            ccp.addField("private " + InvocationHandler.class.getName() + " handler;");
            // 添加实例对象InvokerInvocationHandler hanler，添加参数为InvokerInvocationHandler的构造器
            ccp.addConstructor(Modifier.PUBLIC, new Class<?>[]{InvocationHandler.class}, new Class<?>[0], "handler=$1;");
            // 添加默认无参构造器
            ccp.addDefaultConstructor();
            // 使用toClass方法生成对应的字节码
            Class<?> clazz = ccp.toClass();
            clazz.getField("methods").set(null, methods.toArray(new Method[0]));

            // 生成的字节码对象为服务接口的代理对象
            // create Proxy class.
            //类名为 org.apache.dubbo.common.bytecode.Proxy + id
            String fcn = Proxy.class.getName() + id;
            ccm = ClassGenerator.newInstance(cl);
            ccm.setClassName(fcn);
            ccm.addDefaultConstructor();
            ccm.setSuperClass(Proxy.class);
            //返回 代理对象 （实现了 newInstance方法...）
            ccm.addMethod("public Object newInstance(" + InvocationHandler.class.getName() + " h){ return new " + pcn + "($1); }");
            Class<?> pc = ccm.toClass();
            proxy = (Proxy) pc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // 重置类构造器
            // release ClassGenerator
            if (ccp != null) {
                ccp.release();
            }
            if (ccm != null) {
                ccm.release();
            }
            synchronized (cache) {
                if (proxy == null) {
                    cache.remove(key);
                } else {
                    //设置缓存
                    cache.put(key, new WeakReference<Proxy>(proxy));
                }
                cache.notifyAll();
            }
        }
        return proxy;
    }

    private static String asArgument(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (Boolean.TYPE == cl) {
                return name + "==null?false:((Boolean)" + name + ").booleanValue()";
            }
            if (Byte.TYPE == cl) {
                return name + "==null?(byte)0:((Byte)" + name + ").byteValue()";
            }
            if (Character.TYPE == cl) {
                return name + "==null?(char)0:((Character)" + name + ").charValue()";
            }
            if (Double.TYPE == cl) {
                return name + "==null?(double)0:((Double)" + name + ").doubleValue()";
            }
            if (Float.TYPE == cl) {
                return name + "==null?(float)0:((Float)" + name + ").floatValue()";
            }
            if (Integer.TYPE == cl) {
                return name + "==null?(int)0:((Integer)" + name + ").intValue()";
            }
            if (Long.TYPE == cl) {
                return name + "==null?(long)0:((Long)" + name + ").longValue()";
            }
            if (Short.TYPE == cl) {
                return name + "==null?(short)0:((Short)" + name + ").shortValue()";
            }
            throw new RuntimeException(name + " is unknown primitive type.");
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    /**
     * get instance with default handler.
     *
     * @return instance.
     */
    public Object newInstance() {
        return newInstance(THROW_UNSUPPORTED_INVOKER);
    }

    /**
     * get instance with special handler.
     *
     * @return instance.
     */
    abstract public Object newInstance(InvocationHandler handler);
}
