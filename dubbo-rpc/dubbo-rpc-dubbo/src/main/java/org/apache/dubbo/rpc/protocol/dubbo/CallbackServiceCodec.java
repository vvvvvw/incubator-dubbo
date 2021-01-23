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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcInvocation;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * callback service helper
 */
class CallbackServiceCodec {
    private static final Logger logger = LoggerFactory.getLogger(CallbackServiceCodec.class);

    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private static final DubboProtocol protocol = DubboProtocol.getDubboProtocol();
    private static final byte CALLBACK_NONE = 0x0;
    private static final byte CALLBACK_CREATE = 0x1;
    private static final byte CALLBACK_DESTROY = 0x2;
    private static final String INV_ATT_CALLBACK_KEY = "sys_callback_arg-";

    //判断是否为callback和callback类型 CALLBACK_NONE/CALLBACK_CREATE/CALLBACK_DESTROY
    private static byte isCallBack(URL url, String methodName, int argIndex) {
        // parameter callback rule: method-name.parameter-index(starting from 0).callback
        byte isCallback = CALLBACK_NONE;
        if (url != null) {
            String callback = url.getParameter(methodName + "." + argIndex + ".callback");
            if (callback != null) {
                if (callback.equalsIgnoreCase("true")) {
                    isCallback = CALLBACK_CREATE;
                } else if (callback.equalsIgnoreCase("false")) {
                    isCallback = CALLBACK_DESTROY;
                }
            }
        }
        return isCallback;
    }

    /**
     * export or unexport callback service on client side
     * 导出或者 关闭在客户端的 callback service
     * @param channel
     * @param url
     * @param clazz
     * @param inst
     * @param export
     * @throws IOException
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String exportOrUnexportCallbackService(Channel channel, URL url, Class clazz, Object inst, Boolean export) throws IOException {
        // 获取 callbackservice实例的 唯一id(java根据对象在内存中的地址算出来的一个数值)
        int instid = System.identityHashCode(inst);

        // 由于会调用共用的export，所以这个 callback 的服务和 主 服务共享一个 service
        // 以下为构造参数过程
        // 1.构造 参数
        //isserver = false,is_callback_service=false, group:{调用url的group},methods:CallbackService接口的方法列表(方法名使用,分隔),url的其他 parameter参数(去除version参数)，interface:CallbackService接口的全限定类名
        //2.拼接url
        // dubbo://客户端ip:客户端端口/callBackService接口的全限定类名.callBackService的实例内存地址计算出来的hash值
        // 参数: isserver = false,is_callback_service=false, group:{调用url的group},methods:CallbackService的方法(方法名,使用,分隔),url的其他 parameter参数(去除version参数)，interface:CallbackService的全限定类名
        Map<String, String> params = new HashMap<>(3);
        // no need to new client again
        params.put(Constants.IS_SERVER_KEY, Boolean.FALSE.toString());
        // mark it's a callback, for troubleshooting
        params.put(Constants.IS_CALLBACK_SERVICE, Boolean.TRUE.toString());
        String group = (url == null ? null : url.getParameter(Constants.GROUP_KEY));
        if (group != null && group.length() > 0) {
            params.put(Constants.GROUP_KEY, group);
        }
        // add method, for verifying against method, automatic fallback (see dubbo protocol)
        params.put(Constants.METHODS_KEY, StringUtils.join(Wrapper.getWrapper(clazz).getDeclaredMethodNames(), ","));

        Map<String, String> tmpMap = new HashMap<>(url.getParameters());
        tmpMap.putAll(params);
        tmpMap.remove(Constants.VERSION_KEY);// doesn't need to distinguish version for callback
        tmpMap.put(Constants.INTERFACE_KEY, clazz.getName());
        URL exportUrl = new URL(DubboProtocol.NAME, channel.getLocalAddress().getAddress().getHostAddress(), channel.getLocalAddress().getPort(), clazz.getName() + "." + instid, tmpMap);

        // no need to generate multiple exporters for different channel in the same JVM, cache key cannot collide.
        //callback.service.instid.{callBackService的实例内存地址计算出来的hash值}
        String cacheKey = getClientSideCallbackServiceCacheKey(instid);
        //callback.service.instid.{callbackservice接口的全限定类名}.COUNT
        String countKey = getClientSideCountKey(clazz.getName());
        if (export) {
            // one channel can have multiple callback instances, no need to re-export for different instance.
            //如果 cacheKey在channel中还没有缓存
            if (!channel.hasAttribute(cacheKey)) {
                if (!isInstancesOverLimit(channel, url, clazz.getName(), instid, false)) {
                    // 构造一个callback 的Invoker
                    Invoker<?> invoker = proxyFactory.getInvoker(inst, clazz, exportUrl);
                    // should destroy resource?
                    // dubbo协议 直接暴露该服务，获取一个 Exporter
                    Exporter<?> exporter = protocol.export(invoker);
                    // this is used for tracing if instid has published service or not.
                    // 将Exporter 放入channel 中，key为 callback.service.instid.{callBackService的实例内存地址计算出来的hash值}
                    channel.setAttribute(cacheKey, exporter);
                    logger.info("Export a callback service :" + exportUrl + ", on " + channel + ", url is: " + url);
                    // 更新计数缓存 callback.service.instid.{callbackservice接口的全限定类名}.COUNT
                    increaseInstanceCount(channel, countKey);
                }
            }
        } else {
            //如果是 销毁，则从 销毁该服务，并从 缓存中删除，更新 计数缓存
            if (channel.hasAttribute(cacheKey)) {
                Exporter<?> exporter = (Exporter<?>) channel.getAttribute(cacheKey);
                exporter.unexport();
                channel.removeAttribute(cacheKey);
                decreaseInstanceCount(channel, countKey);
            }
        }
        // 返回 callbackservice实例的 唯一id(java根据对象在内存中的地址算出来的一个数值)
        return String.valueOf(instid);
    }

    /**
     * refer or destroy callback service on server side
     *
     * @param url
     */
    @SuppressWarnings("unchecked")
    private static Object referOrDestroyCallbackService(Channel channel, URL url, Class<?> clazz, Invocation inv, int instid, boolean isRefer) {
        Object proxy = null;
        // invoker 缓存对象 的key：callback.service.proxy.12503143.com.anla.rpc.callback.provider.service.CallbackListener.654342195.invoker
        //构建服务端 缓存对象的key
        // callback.service.proxy.{channel的内存地址计算到的hash值}.{回调接口全限定类名}.{客户端callbackservice实例在内存中的地址计算出来的hash值}.invoker
        String invokerCacheKey = getServerSideCallbackInvokerCacheKey(channel, clazz.getName(), instid);
        // 代理缓存对象key：callback.service.proxy.12503143.com.anla.rpc.callback.provider.service.CallbackListener.654342195
        // callback.service.proxy.{channel的内存地址计算到的hash值}.{回调接口全限定类名}.{客户端callbackservice实例在内存中的地址计算出来的hash值}
        String proxyCacheKey = getServerSideCallbackServiceCacheKey(channel, clazz.getName(), instid);
        // 判断当前 channel 是否已经缓存了代理对象。
        proxy = channel.getAttribute(proxyCacheKey);
        // count 的key  callback.service.proxy.12503143.com.anla.rpc.callback.provider.service.CallbackListener.COUNT
        ////callback.service.proxy.{channel实例的内存地址计算出来的hash值}.{回调接口的全限定类名}.COUNT
        String countkey = getServerSideCountKey(channel, clazz.getName());
        //如果创建 服务端callback代理对象
        if (isRefer) {
            if (proxy == null) {
                //构建url: callback://{原服务端ip:原服务端端口}/{回调接口的全限定类名}?interface={回调接口的全限定类名}
                URL referurl = URL.valueOf("callback://" + url.getAddress() + "/" + clazz.getName() + "?" + Constants.INTERFACE_KEY + "=" + clazz.getName());
                referurl = referurl.addParametersIfAbsent(url.getParameters()).removeParameter(Constants.METHODS_KEY);
                // 以下判断是否超出 服务的 callback 阈值
                if (!isInstancesOverLimit(channel, referurl, clazz.getName(), instid, true)) {
                    // 构造一个Invoker
                    @SuppressWarnings("rawtypes")
                    Invoker<?> invoker = new ChannelWrappedInvoker(clazz, channel, referurl, String.valueOf(instid));
                    // 使用 JavassistProxyFactory 生成一个由 InvokerInvocationHandler+ AsyncToSyncInvoker 的包装的invoker
                    proxy = proxyFactory.getProxy(invoker);
                    // 将代理类设置到 channel的缓存，key:callback.service.proxy.{channel的内存地址计算到的hash值}.{回调接口全限定类名}.{客户端callbackservice实例在内存中的地址计算出来的hash值}
                    channel.setAttribute(proxyCacheKey, proxy);
                    //将创建的invoker设置到channel缓存，key：callback.service.proxy.{channel的内存地址计算到的hash值}.{回调接口全限定类名}.{客户端callbackservice实例在内存中的地址计算出来的hash值}.invoker
                    channel.setAttribute(invokerCacheKey, invoker);
                    //count缓存+1
                    increaseInstanceCount(channel, countkey);

                    //convert error fail fast .
                    //ignore concurrent problem.
                    //设置channel 缓存：channel.callback.invokers.key value:Set<invokers>
                    Set<Invoker<?>> callbackInvokers = (Set<Invoker<?>>) channel.getAttribute(Constants.CHANNEL_CALLBACK_KEY);
                    if (callbackInvokers == null) {
                        callbackInvokers = new ConcurrentHashSet<Invoker<?>>(1);
                        callbackInvokers.add(invoker);
                        channel.setAttribute(Constants.CHANNEL_CALLBACK_KEY, callbackInvokers);
                    }
                    logger.info("method " + inv.getMethodName() + " include a callback service :" + invoker.getUrl() + ", a proxy :" + invoker + " has been created.");
                }
            }
        } else {
            if (proxy != null) {
                // 从channel 中拿出缓存并销毁
                Invoker<?> invoker = (Invoker<?>) channel.getAttribute(invokerCacheKey);
                try {
                    Set<Invoker<?>> callbackInvokers = (Set<Invoker<?>>) channel.getAttribute(Constants.CHANNEL_CALLBACK_KEY);
                    if (callbackInvokers != null) {
                        callbackInvokers.remove(invoker);
                    }
                    invoker.destroy();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
                // cancel refer, directly remove from the map
                channel.removeAttribute(proxyCacheKey);
                channel.removeAttribute(invokerCacheKey);
                //修改缓存计数
                decreaseInstanceCount(channel, countkey);
            }
        }
        return proxy;
    }

    private static String getClientSideCallbackServiceCacheKey(int instid) {
        return Constants.CALLBACK_SERVICE_KEY + "." + instid;
    }

    //callback.service.proxy.{channel的内存地址计算到的hash值}.{接口全限定类名}.{客户端callbackservice实例在内存中的地址计算出来的hash值}
    private static String getServerSideCallbackServiceCacheKey(Channel channel, String interfaceClass, int instid) {
        return Constants.CALLBACK_SERVICE_PROXY_KEY + "." + System.identityHashCode(channel) + "." + interfaceClass + "." + instid;
    }

    //callback.service.proxy.{channel的内存地址计算到的hash值}.{接口全限定类名}.{客户端callbackservice实例在内存中的地址计算出来的hash值}.invoker
    private static String getServerSideCallbackInvokerCacheKey(Channel channel, String interfaceClass, int instid) {
        return getServerSideCallbackServiceCacheKey(channel, interfaceClass, instid) + "." + "invoker";
    }

    private static String getClientSideCountKey(String interfaceClass) {
        //callback.service.instid.{callbackservice的全限定类名}.COUNT
        return Constants.CALLBACK_SERVICE_KEY + "." + interfaceClass + ".COUNT";
    }

    private static String getServerSideCountKey(Channel channel, String interfaceClass) {
        //callback.service.proxy.{channel实例的内存地址计算出来的hash值}.{接口的全限定类名}.COUNT
        return Constants.CALLBACK_SERVICE_PROXY_KEY + "." + System.identityHashCode(channel) + "." + interfaceClass + ".COUNT";
    }

    // 是否 channel中缓存的 callbackService接口对应的 callbackService实例 超过了数量限制
    private static boolean isInstancesOverLimit(Channel channel, URL url, String interfaceClass, int instid, boolean isServer) {
        //服务端：//callback.service.proxy.{channel实例的内存地址计算出来的hash值}.{callbackService接口的全限定类名}.COUNT
        //客户端：//callback.service.instid.{callbackservice接口的全限定类名}.COUNT
        Integer count = (Integer) channel.getAttribute(isServer ? getServerSideCountKey(channel, interfaceClass) : getClientSideCountKey(interfaceClass));
        // 每种 callbackService接口对应的 callbackService实例 的 数量限制，key:callbacks 默认值：1
        int limit = url.getParameter(Constants.CALLBACK_INSTANCES_LIMIT_KEY, Constants.DEFAULT_CALLBACK_INSTANCES);
        if (count != null && count >= limit) {
            //client side error
            throw new IllegalStateException("interface " + interfaceClass + " `s callback instances num exceed providers limit :" + limit
                    + " ,current num: " + (count + 1) + ". The new callback service will not work !!! you can cancle the callback service which exported before. channel :" + channel);
        } else {
            return false;
        }
    }

    private static void increaseInstanceCount(Channel channel, String countkey) {
        try {
            //ignore concurrent problem?
            Integer count = (Integer) channel.getAttribute(countkey);
            if (count == null) {
                count = 1;
            } else {
                count++;
            }
            channel.setAttribute(countkey, count);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private static void decreaseInstanceCount(Channel channel, String countkey) {
        try {
            Integer count = (Integer) channel.getAttribute(countkey);
            if (count == null || count <= 0) {
                return;
            } else {
                count--;
            }
            channel.setAttribute(countkey, count);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public static Object encodeInvocationArgument(Channel channel, RpcInvocation inv, int paraIndex) throws IOException {
        // get URL directly
        URL url = inv.getInvoker() == null ? null : inv.getInvoker().getUrl();
        byte callbackStatus = isCallBack(url, inv.getMethodName(), paraIndex);
        Object[] args = inv.getArguments();
        Class<?>[] pts = inv.getParameterTypes();
        switch (callbackStatus) {
            //如果没有指定callback参数，则返回正常参数
            case CallbackServiceCodec.CALLBACK_NONE:
                return args[paraIndex];
            case CallbackServiceCodec.CALLBACK_CREATE:
                // sys_callback_arg-{参数索引}
                inv.setAttachment(INV_ATT_CALLBACK_KEY + paraIndex, exportOrUnexportCallbackService(channel, url, pts[paraIndex], args[paraIndex], true));
                return null;
            case CallbackServiceCodec.CALLBACK_DESTROY:
                inv.setAttachment(INV_ATT_CALLBACK_KEY + paraIndex, exportOrUnexportCallbackService(channel, url, pts[paraIndex], args[paraIndex], false));
                return null;
            default:
                return args[paraIndex];
        }
    }

    //解析url，如果是callback参数，则创建或者销毁callback代理对象，否则返回
    public static Object decodeInvocationArgument(Channel channel, RpcInvocation inv, Class<?>[] pts, int paraIndex, Object inObject) throws IOException {
        // if it's a callback, create proxy on client side, callback interface on client side can be invoked through channel
        // need get URL from channel and env when decode
        // 如果是callback 类型，则创建client 端的代理，即这个代理对象可以发起对client端的远程调用
        URL url = null;
        try {
            // 解析出url（导出服务的url）（不是 回调服务的）
            url = DubboProtocol.getDubboProtocol().getInvoker(channel, inv).getUrl();
        } catch (RemotingException e) {
            if (logger.isInfoEnabled()) {
                logger.info(e.getMessage(), e);
            }
            return inObject;
        }
        // 根据key({methodName}.{argIndex}.callback)解析callback类型  CALLBACK_NONE/CALLBACK_CREATE/CALLBACK_DESTROY
        byte callbackstatus = isCallBack(url, inv.getMethodName(), paraIndex);
        switch (callbackstatus) {
            case CallbackServiceCodec.CALLBACK_NONE:
                // 普通参数
                return inObject;
            case CallbackServiceCodec.CALLBACK_CREATE:
                try {
                    // 创建callback
                    return referOrDestroyCallbackService(channel, url, pts[paraIndex], inv, Integer.parseInt(inv.getAttachment(INV_ATT_CALLBACK_KEY + paraIndex)), true);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    throw new IOException(StringUtils.toString(e));
                }
            case CallbackServiceCodec.CALLBACK_DESTROY:
                try {
                    // 销毁callback
                    return referOrDestroyCallbackService(channel, url, pts[paraIndex], inv, Integer.parseInt(inv.getAttachment(INV_ATT_CALLBACK_KEY + paraIndex)), false);
                } catch (Exception e) {
                    throw new IOException(StringUtils.toString(e));
                }
            default:
                return inObject;
        }
    }
}
