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
package org.apache.dubbo.rpc.model;

import org.apache.dubbo.common.Constants;

import java.lang.reflect.Method;
import java.util.Map;

public class ConsumerMethodModel {
    private final Method method;
    //    private final boolean isCallBack;
//    private final boolean isFuture;
    private final String[] parameterTypes;
    private final Class<?>[] parameterClasses;
    private final Class<?> returnClass;
    private final String methodName;
    private final boolean generic;

    private final AsyncMethodInfo asyncInfo;


    public ConsumerMethodModel(Method method, Map<String, Object> attributes) {
        this.method = method;
        this.parameterClasses = method.getParameterTypes();
        this.returnClass = method.getReturnType();
        this.parameterTypes = this.createParamSignature(parameterClasses);
        this.methodName = method.getName();
        this.generic = methodName.equals(Constants.$INVOKE) && parameterTypes != null && parameterTypes.length == 3;

        if (attributes != null) {
            asyncInfo = (AsyncMethodInfo) attributes.get(methodName);
        } else {
            asyncInfo = null;
        }
    }

    public Method getMethod() {
        return method;
    }

    public Class<?> getReturnClass() {
        return returnClass;
    }

    public AsyncMethodInfo getAsyncInfo() {
        return asyncInfo;
    }

    public String getMethodName() {
        return methodName;
    }

    public String[] getParameterTypes() {
        return parameterTypes;
    }

    private String[] createParamSignature(Class<?>[] args) {
        if (args == null || args.length == 0) {
            return new String[]{};
        }
        String[] paramSig = new String[args.length];
        for (int x = 0; x < args.length; x++) {
            paramSig[x] = args[x].getName();
        }
        return paramSig;
    }


    public boolean isGeneric() {
        return generic;
    }

    public Class<?>[] getParameterClasses() {
        return parameterClasses;
    }


    public static class AsyncMethodInfo {
        // callback instance when async-call is invoked
        //请求远程服务之前的回调方法的对象，调用参数使用 传给远程服务使用的参数
        private Object oninvokeInstance;

        // callback method when async-call is invoked
        //请求远程服务之前的回调方法，调用参数使用 传给远程服务使用的参数
        private Method oninvokeMethod;

        // callback instance when async-call is returned
        //请求远程服务之后正常返回的回调方法的对象
        private Object onreturnInstance;

        // callback method when async-call is returned
        //请求远程服务之后正常返回的回调方法，参数为 返回值
        //如果只有一个参数，则第一个参数为 返回结果
        //如果只有两个参数，且第二个参数是 Object[]类型，则将 返回结果赋给第一个参数，args赋给 第二个参数
        //否则，第一个参数为返回结果，后面依次赋值参数
        private Method onreturnMethod;

        // callback instance when async-call has exception thrown
        //请求远程服务之前回调方法执行异常或者 请求远程服务异常的回调方法的对象
        private Object onthrowInstance;

        // callback method when async-call has exception thrown
        //请求远程服务之前回调方法执行异常或者 请求远程服务异常的回调方法，第一个参数需要是 抛出的异常类型
        //如果只有一个参数，则第一个参数为 抛出的异常
        //如果只有两个参数，且第二个参数是 Object[]类型，则将 异常赋给第一个参数，args赋给 第二个参数
        //否则，第一个参数为异常类型，后面依次赋值参数
        private Method onthrowMethod;

        public Object getOninvokeInstance() {
            return oninvokeInstance;
        }

        public void setOninvokeInstance(Object oninvokeInstance) {
            this.oninvokeInstance = oninvokeInstance;
        }

        public Method getOninvokeMethod() {
            return oninvokeMethod;
        }

        public void setOninvokeMethod(Method oninvokeMethod) {
            this.oninvokeMethod = oninvokeMethod;
        }

        public Object getOnreturnInstance() {
            return onreturnInstance;
        }

        public void setOnreturnInstance(Object onreturnInstance) {
            this.onreturnInstance = onreturnInstance;
        }

        public Method getOnreturnMethod() {
            return onreturnMethod;
        }

        public void setOnreturnMethod(Method onreturnMethod) {
            this.onreturnMethod = onreturnMethod;
        }

        public Object getOnthrowInstance() {
            return onthrowInstance;
        }

        public void setOnthrowInstance(Object onthrowInstance) {
            this.onthrowInstance = onthrowInstance;
        }

        public Method getOnthrowMethod() {
            return onthrowMethod;
        }

        public void setOnthrowMethod(Method onthrowMethod) {
            this.onthrowMethod = onthrowMethod;
        }
    }
}
