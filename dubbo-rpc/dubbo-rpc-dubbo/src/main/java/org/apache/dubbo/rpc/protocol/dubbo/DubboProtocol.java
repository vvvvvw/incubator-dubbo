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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    //默认端口号
    public static final int DEFAULT_PORT = 20880;
    //回调名称
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    //dubbo协议的单例
    private static DubboProtocol INSTANCE;

    /**
     * <host:port,Exchanger>
     */
    //信息交换服务器集合 key：host:port  value：ExchangeServer
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<>();
    /**
     * <host:port,Exchanger>
     */
    //共享客户端，信息交换客户端集合 Map<服务端地址，List<引用计数ExchangeClient>>
    private final Map<String, List<ReferenceCountExchangeClient>> referenceClientMap = new ConcurrentHashMap<>();
    //锁集合
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    //序列化类名集合
    private final Set<String> optimizers = new ConcurrentHashSet<>();
    /**
     * consumer side export a stub service for dispatching event
     * servicekey-stubmethods
     */
    //本地存根服务方法集合
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<>();

    //实现了基于dubbo协议等连接、取消连接、回复请求结果等方法。
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        /**
         * 回复请求结果，返回的是请求结果
         * @param channel
         * @param message
         * @return
         * @throws RemotingException
         */
        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            // 如果请求消息不属于会话域，则抛出异常
            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            //强制类型转化
            Invocation inv = (Invocation) message;
            // 获得暴露的服务invoker
            Invoker<?> invoker = getInvoker(channel, inv);
            // need to consider backward-compatibility if it's a callback
            // 如果是回调服务
            if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                // 获得 方法定义
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                // 如果只有一个方法定义
                if (methodsStr == null || !methodsStr.contains(",")) {
                    // 设置会话域中的方法定义和 该方法定义是否一致
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    // 分割不同的方法
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        // 如果方法不止一个，则分割后遍历查询，找到了则设置为true
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                // 如果没有该方法，则打印告警日志
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            RpcContext rpcContext = RpcContext.getContext();
            // 设置远程地址
            rpcContext.setRemoteAddress(channel.getRemoteAddress());
            // 调用下一个调用链
            Result result = invoker.invoke(inv);

            //  返回CompletableFuture<Result>
            if (result instanceof AsyncRpcResult) {
                return ((AsyncRpcResult) result).getResultFuture().thenApply(r -> (Object) r);

            } else {
                return CompletableFuture.completedFuture(result);
            }
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, Constants.ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, Constants.ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            invocation.setAttachment(Constants.PATH_KEY, url.getPath());
            invocation.setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
            invocation.setAttachment(Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
            invocation.setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY));
            if (url.getParameter(Constants.STUB_EVENT_KEY, false)) {
                invocation.setAttachment(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            // load
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME);
        }

        return INSTANCE;
    }

    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    //如果是 作为客户端(对于本地回调来说，服务端就是 本地回调服务的客户端)
    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    //todo ？ 这边本地存根和回调的逻辑是没有看懂的
    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        //本地服务端口
        int port = channel.getLocalAddress().getPort();
        //请求的服务   回调服务接口全限定类名
        String path = inv.getAttachments().get(Constants.PATH_KEY);

        //是否 是客户端本地存根
        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(Constants.STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            //todo 为什么 客户端本地存根和服务端处理有什么关系 如果是 客户端本地存根，则设置为客户端端口
            port = channel.getRemoteAddress().getPort();
        }

        //是否是作为本地回调的客户端，且不是本地存根 //如果是 作为客户端(对于本地回调来说，服务端就是 客户端本地回调服务的客户端)
        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            // path = {回调服务接口的全限定类名}.null
            path += "." + inv.getAttachments().get(Constants.CALLBACK_SERVICE_KEY);
            // 向attachments中添加 _isCallBackServiceInvoke:true
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(port, path, inv.getAttachments().get(Constants.VERSION_KEY), inv.getAttachments().get(Constants.GROUP_KEY));
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                    ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);
        }

        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    //基于dubbo协议的服务暴露，除了对于存根服务和本地服务进行标记以外，打开服务和序列化分别在openServer和optimizeSerialization中实现
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();

        // 得到服务key  group+"/"+serviceName+":"+serviceVersion+":"+port
        // export service.
        String key = serviceKey(url);
        // 创建exporter
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        // 加入到集合
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(Constants.STUB_EVENT_KEY, Constants.DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(Constants.IS_CALLBACK_SERVICE, false);
        // 如果是本地存根事件而不是回调服务，只有消费端才会走到这里
        if (isStubSupportEvent && !isCallbackservice) {
            // 获得本地存根的方法
            String stubServiceMethods = url.getParameter(Constants.STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(Constants.INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            } else {
                // 加入集合
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }

        // 打开服务
        openServer(url);
        // 序列化
        optimizeSerialization(url);

        return exporter;
    }

    //打开服务。其中的逻辑其实是把服务对象放入集合中进行缓存，如果该地址对应的服务器不存在，则调用createServer创建一个服务器对象。
    private void openServer(URL url) {
        // find server.
        String key = url.getAddress();
        //client can export a service which's only for server to invoke
        // 客户端是否可以暴露仅供服务器调用的服务（todo 这个key不懂是什么意思，服务端暴露的时候 value为null）
        boolean isServer = url.getParameter(Constants.IS_SERVER_KEY, true);
        // 如果是的话
        if (isServer) {
            // 获得信息交换服务器
            ExchangeServer server = serverMap.get(key);
            if (server == null) {
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {
                        // 重新创建服务器对象，然后放入集合
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {
                // 重置
                // server supports reset, use together with override
                server.reset(url);
            }
        }
    }

    //根据url携带的远程通信实现方法来创建一个服务器对象。
    /*
    1.对服务端远程通讯服务端实现方式配置是否支持的检测
    2.创建服务器实例，也就是调用bind()方法
    3.对服务端远程通讯客户端实现方式配置是否支持的检测
     */
    private ExchangeServer createServer(URL url) {
        // 服务器关闭时发送readonly事件，默认情况下启用
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default
                .addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // enable heartbeat by default
                // 心跳默认间隔一分钟
                .addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT))
                //添加编解码器DubboCodec实现
                .addParameter(Constants.CODEC_KEY, DubboCodec.NAME)
                .build();
        // 获得远程通讯服务端实现方式，默认用netty3
        String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);

        //如果没有该配置，则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {
            // 启动服务器
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        // 获得设置的客户端侧远程通信方式
        str = url.getParameter(Constants.CLIENT_KEY);
        if (str != null && str.length() > 0) {
            // 获得远程通信的实现集合
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            // 如果客户端侧设置的远程通信方式不在支持的方式中，则抛出异常
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        return server;
    }

    //把序列化的类放入到集合，以便进行序列化
    private void optimizeSerialization(URL url) throws RpcException {
        //// 获得类名（todo 开启优化点）
        String className = url.getParameter(Constants.OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            // 加载类
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            // 强制类型转化为SerializationOptimizer
            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            // 遍历序列化的类，把该类放入到集合进行缓存
            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            // 加入到集合
            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        optimizeSerialization(url);

        // 创建一个DubboInvoker实例
        // create rpc invoker.
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        // 加入到集合中
        invokers.add(invoker);

        return invoker;
    }

    //获得客户端集合的方法，分为共享客户端和非共享客户端。共享客户端是
    // 共用同一个连接，非共享客户端是每个客户端都有自己的一个连接
    private ExchangeClient[] getClients(URL url) {
        // whether to share connection

        // 一个连接是否对于一个服务
        // TODO: 共享客户端是指什么  by 15258 2019/6/9 17:02
        boolean useShareConnect = false;

        // 获得url中连接共享的配置 默认为0
        int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);
        List<ReferenceCountExchangeClient> shareClients = null;
        // 如果为0，则是共享连接，并且连接数为1；否则一个服务一个连接
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {
            useShareConnect = true;

            /**
             * The xml configuration should have a higher priority than properties.
             */
            String shareConnectionsStr = url.getParameter(Constants.SHARE_CONNECTIONS_KEY, (String) null);
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigUtils.getProperty(Constants.SHARE_CONNECTIONS_KEY,
                    Constants.DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
            shareClients = getSharedClient(url, connections);
        }

        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            // 如果共享，则获得共享客户端对象，否则新建客户端
            if (useShareConnect) {
                clients[i] = shareClients.get(i);

            } else {
                clients[i] = initClient(url);
            }
        }

        return clients;
    }

    /**
     * Get shared connection
     *
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        String key = url.getAddress();
        // 从集合中取出客户端对象
        List<ReferenceCountExchangeClient> clients = referenceClientMap.get(key);

        // 如果不为空并且没关闭连接，则计数器加1，返回
        //todo 如果第一次和第二次 共享连接数设置的数量不一致？
        if (checkClientCanUse(clients)) {
            batchClientRefIncr(clients);
            return clients;
        }

        locks.putIfAbsent(key, new Object());
        synchronized (locks.get(key)) {
            // 如果集合中有该key
            clients = referenceClientMap.get(key);
            // dubbo check
            // 则直接返回client
            if (checkClientCanUse(clients)) {
                batchClientRefIncr(clients);
                return clients;
            }

            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is
            if (CollectionUtils.isEmpty(clients)) {
                clients = buildReferenceCountExchangeClientList(url, connectNum);
                referenceClientMap.put(key, clients);

            } else {
                for (int i = 0; i < clients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = clients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    //重建不可用的节点
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        clients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }

                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }

            /**
             * I understand that the purpose of the remove operation here is to avoid the expired url key
             * always occupying this memory space.
             */
            // 从对象锁中移除
            locks.remove(key);

            return clients;
        }
    }

    /**
     * Check if the client list is all available
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    //是否 所有客户端都可用
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Add client references in bulk
     *
     * @param referenceCountExchangeClients
     */
    //增加引用计数
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new CopyOnWriteArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        ExchangeClient exchangeClient = initClient(url);

        return new ReferenceCountExchangeClient(exchangeClient);
    }

    /**
     * Create new connection
     *
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        String str = url.getParameter(Constants.CLIENT_KEY, url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);

            } else {
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    public void destroy() {
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ExchangeServer server = serverMap.remove(key);

            if (server == null) {
                continue;
            }

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }

                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            List<ReferenceCountExchangeClient> clients = referenceClientMap.remove(key);

            if (CollectionUtils.isEmpty(clients)) {
                continue;
            }

            for (ReferenceCountExchangeClient client : clients) {
                closeReferenceCountExchangeClient(client);
            }
        }

        stubServiceMethodsMap.clear();
        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /**
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }
}
