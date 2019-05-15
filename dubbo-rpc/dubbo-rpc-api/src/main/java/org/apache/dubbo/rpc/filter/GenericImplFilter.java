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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.beanutil.JavaBeanAccessor;
import org.apache.dubbo.common.beanutil.JavaBeanDescriptor;
import org.apache.dubbo.common.beanutil.JavaBeanSerializeUtil;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.PojoUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.service.GenericException;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GenericImplInvokerFilter
 */
//对于泛化调用的序列化检查和处理，它是消费者侧的过滤器。
@Activate(group = Constants.CONSUMER, value = Constants.GENERIC_KEY, order = 20000)
public class GenericImplFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(GenericImplFilter.class);

    //参数集合
    private static final Class<?>[] GENERIC_PARAMETER_TYPES = new Class<?>[]{String.class, String[].class, Object[].class};

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // TODO: 服务端有三种序列化方式，为什么客户端序列化 每次只有两种  by 15258 2019/5/14 23:26
        // 获得泛化的值
        String generic = invoker.getUrl().getParameter(Constants.GENERIC_KEY);
        //如果是 generic 不为空，但是方法不是 泛化调用
        //其实 转换只有两种：javabean或者pojo
        // 如果该值是nativejava或者bean或者true，并且不是泛化调用
        if (ProtocolUtils.isGeneric(generic)
                && !Constants.$INVOKE.equals(invocation.getMethodName())
                && invocation instanceof RpcInvocation) {
            RpcInvocation invocation2 = (RpcInvocation) invocation;
            // 获得方法名称
            String methodName = invocation2.getMethodName();
            // 获得参数类型集合
            Class<?>[] parameterTypes = invocation2.getParameterTypes();
            // 获得参数集合
            Object[] arguments = invocation2.getArguments();

            // 把参数类型的名称放入集合
            String[] types = new String[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                //获取参数类名
                types[i] = ReflectUtils.getName(parameterTypes[i]);
            }

            Object[] args;
            // 使用javabean序列化参数
            if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                args = new Object[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    //按照javabean的方法来解析
                    args[i] = JavaBeanSerializeUtil.serialize(arguments[i], JavaBeanAccessor.METHOD);
                }
            } else {
                //使用 pojo解析
                args = PojoUtils.generalize(arguments);
            }

            // 重新把序列化的参数放入
            invocation2.setMethodName(Constants.$INVOKE);
            invocation2.setParameterTypes(GENERIC_PARAMETER_TYPES);
            invocation2.setArguments(new Object[]{methodName, types, args});
            // 调用下一个调用链
            Result result = invoker.invoke(invocation2);

            if (!result.hasException()) {
                Object value = result.getValue();
                try {
                    Method method = invoker.getInterface().getMethod(methodName, parameterTypes);
                    if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                        if (value == null) {
                            //如果为空
                            return new RpcResult(value);
                        } else if (value instanceof JavaBeanDescriptor) {
                            // 用javabean方式反序列化
                            return new RpcResult(JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) value));
                        } else {
                            throw new RpcException(
                                    "The type of result value is " +
                                            value.getClass().getName() +
                                            " other than " +
                                            JavaBeanDescriptor.class.getName() +
                                            ", and the result is " +
                                            value);
                        }
                    } else {
                        // 直接反序列化pojo类型
                        return new RpcResult(PojoUtils.realize(value, method.getReturnType(), method.getGenericReturnType()));
                    }
                } catch (NoSuchMethodException e) {
                    throw new RpcException(e.getMessage(), e);
                }
                // 如果调用链中有异常抛出，并且是GenericException类型的异常
            } else if (result.getException() instanceof GenericException) {
                GenericException exception = (GenericException) result.getException();
                try {
                    // 获得异常类名
                    String className = exception.getExceptionClass();
                    Class<?> clazz = ReflectUtils.forName(className);
                    Throwable targetException = null;
                    Throwable lastException = null;
                    try {
                        targetException = (Throwable) clazz.newInstance();
                    } catch (Throwable e) {
                        lastException = e;
                        for (Constructor<?> constructor : clazz.getConstructors()) {
                            try {
                                targetException = (Throwable) constructor.newInstance(new Object[constructor.getParameterTypes().length]);
                                break;
                            } catch (Throwable e1) {
                                lastException = e1;
                            }
                        }
                    }
                    //如果序列化成功
                    if (targetException != null) {
                        try {
                            //设置detailMessage字段
                            Field field = Throwable.class.getDeclaredField("detailMessage");
                            if (!field.isAccessible()) {
                                field.setAccessible(true);
                            }
                            field.set(targetException, exception.getExceptionMessage());
                        } catch (Throwable e) {
                            logger.warn(e.getMessage(), e);
                        }
                        result = new RpcResult(targetException);
                    } else if (lastException != null) {
                        throw lastException;
                    }
                } catch (Throwable e) {
                    throw new RpcException("Can not deserialize exception " + exception.getExceptionClass() + ", message: " + exception.getExceptionMessage(), e);
                }
            }
            return result;
        }

        // 如果是泛接口调用
        if (invocation.getMethodName().equals(Constants.$INVOKE)
                && invocation.getArguments() != null
                && invocation.getArguments().length == 3
                && ProtocolUtils.isGeneric(generic)) {

            //获取参数
            Object[] args = (Object[]) invocation.getArguments()[2];
            //如果是javanative的序列化方式
            if (ProtocolUtils.isJavaGenericSerialization(generic)) {

                for (Object arg : args) {
                    // 如果调用参数不是字节数组类型，则抛出异常
                    if (!(byte[].class == arg.getClass())) {
                        error(generic, byte[].class.getName(), arg.getClass().getName());
                    }
                }
                //如果是 javabean的序列化方式
            } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                for (Object arg : args) {
                    // 如果调用消息不是字节数组类型，则抛出异常
                    if (!(arg instanceof JavaBeanDescriptor)) {
                        error(generic, JavaBeanDescriptor.class.getName(), arg.getClass().getName());
                    }
                }
            }

            // 设置附加值
            ((RpcInvocation) invocation).setAttachment(
                    Constants.GENERIC_KEY, invoker.getUrl().getParameter(Constants.GENERIC_KEY));
        }
        return invoker.invoke(invocation);
    }

    /**
     * 抛出错误异常
     * @param generic
     * @param expected
     * @param actual
     * @throws RpcException
     */
    private void error(String generic, String expected, String actual) throws RpcException {
        throw new RpcException(
                "Generic serialization [" +
                        generic +
                        "] only support message type " +
                        expected +
                        " and your message type is " +
                        actual);
    }

}
