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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.support.ProviderConsumerRegTable;
import org.apache.dubbo.registry.support.ProviderInvokerWrapper;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.support.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.Constants.ACCEPT_FOREIGN_IP;
import static org.apache.dubbo.common.Constants.ANY_VALUE;
import static org.apache.dubbo.common.Constants.BIND_IP_KEY;
import static org.apache.dubbo.common.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.common.Constants.CATEGORY_KEY;
import static org.apache.dubbo.common.Constants.CHECK_KEY;
import static org.apache.dubbo.common.Constants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.Constants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.common.Constants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.common.Constants.DEFAULT_DIRECTORY;
import static org.apache.dubbo.common.Constants.DEFAULT_REGISTER_CONSUMER_KEYS;
import static org.apache.dubbo.common.Constants.DEFAULT_REGISTER_PROVIDER_KEYS;
import static org.apache.dubbo.common.Constants.DEFAULT_REGISTRY;
import static org.apache.dubbo.common.Constants.EXPORT_KEY;
import static org.apache.dubbo.common.Constants.EXTRA_KEYS_KEY;
import static org.apache.dubbo.common.Constants.HIDE_KEY_PREFIX;
import static org.apache.dubbo.common.Constants.INTERFACES;
import static org.apache.dubbo.common.Constants.METHODS_KEY;
import static org.apache.dubbo.common.Constants.MONITOR_KEY;
import static org.apache.dubbo.common.Constants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.Constants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.Constants.PROVIDER_PROTOCOL;
import static org.apache.dubbo.common.Constants.QOS_ENABLE;
import static org.apache.dubbo.common.Constants.QOS_PORT;
import static org.apache.dubbo.common.Constants.REFER_KEY;
import static org.apache.dubbo.common.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.common.Constants.REGISTER_KEY;
import static org.apache.dubbo.common.Constants.REGISTRY_KEY;
import static org.apache.dubbo.common.Constants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.Constants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.Constants.SIMPLIFIED_KEY;
import static org.apache.dubbo.common.Constants.VALIDATION_KEY;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;

/**
 * RegistryProtocol
 */
//是对dubbo-rpc-api的依赖集成
    /*
    实现了Protocol接口，也是Protocol接口等扩展类，但是它可以认为并不是一个真正的协议，
    他是实际的协议（dubbo . rmi）包装者，这样客户端的请求在一开始如果没有服务端的信息，
    会先从注册中心拉取服务的注册信息，然后再和服务端直连。
    RegistryProtocol是基于注册中心发现服务提供者的实现协议。
     */
public class RegistryProtocol implements Protocol {

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    private static RegistryProtocol INSTANCE;
    //配置更新监听器
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<>();
    private final Map<String, ServiceConfigurationListener> serviceConfigurationListeners = new ConcurrentHashMap<>();
    private final ProviderConfigurationListener providerConfigurationListener = new ProviderConfigurationListener();
    //To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    //providerurl <--> exporter
    //Map<服务提供者url的string形式，并且去除dynamic参数,exporter>
    private final ConcurrentMap<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<>();
    private Cluster cluster;
    private Protocol protocol;
    private RegistryFactory registryFactory;
    private ProxyFactory proxyFactory;

    public RegistryProtocol() {
        INSTANCE = this;
    }

    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (CollectionUtils.isNotEmptyMap(params)) {
            return params.keySet().stream()
                    .filter(k -> k.startsWith(HIDE_KEY_PREFIX))
                    .toArray(String[]::new);
        } else {
            return new String[0];
        }
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    public void register(URL registryUrl, URL registeredProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.register(registeredProviderUrl);
    }

    public void unregister(URL registryUrl, URL registeredProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.unregister(registeredProviderUrl);
    }
    /*
    从代码上看，我用分割线分成两部分，分别是服务暴露和服务注册。该方法的逻辑大致分为以下几个步骤：
    1.获得服务提供者的url，再通过override数据重新配置url，然后执行doLocalExport()进行服务暴露。
    2.加载注册中心实现类，向注册中心注册服务。
    3.向注册中心进行订阅 override 数据。
    4.创建并返回 DestroyableExporter
     */
    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        // 获得注册中心的url，以 zookeeper 注册中心为例，得到的示例 URL 如下：
        // zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F172.17.48.52%3A20880%2Fcom.alibaba.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        //获得已经注册的服务提供者url
        // dubbo://172.17.48.52:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&dubbo=2.0.2&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello
        URL providerUrl = getProviderUrl(originInvoker);
        // Subscribe the override data
        // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call
        //  the same service. Because the subscribed is cached key with the name of the service, it causes the
        //  subscription information to cover.
        // 获取override 配置订阅 URL
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);
        // 创建override的监听器
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        // 把监听器添加到集合
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        // 根据外部配置覆盖后的提供者url，根据override的配置来覆盖原来的url，
        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        //export invoker
        // 服务暴露
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        // url to registry
        // 根据 URL 加载 Registry 实现类，比如ZookeeperRegistry
        final Registry registry = getRegistry(originInvoker);
        // 返回注册到注册表的url并过滤url参数一次
        final URL registeredProviderUrl = getRegisteredProviderUrl(providerUrl, registryUrl);
        // 生成ProviderInvokerWrapper，它会保存服务提供方和消费方的调用地址和代理对象
        ProviderInvokerWrapper<T> providerInvokerWrapper = ProviderConsumerRegTable.registerProvider(originInvoker,
                registryUrl, registeredProviderUrl);
        // ————————————————————————————————分割线——————————————————————————————————————
        //to judge if we need to delay publish
        // 获取 register 参数，判断是否延迟初始化
        boolean register = registeredProviderUrl.getParameter("register", true);
        // 如果需要注册服务
        if (register) {
            // 向注册中心注册服务
            register(registryUrl, registeredProviderUrl);
            // 设置reg为true，表示服务注册
            providerInvokerWrapper.setReg(true);
        }

        // Deprecated! Subscribe to override rules in 2.6.x or before.
        // 向注册中心进行订阅 override 数据
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        // 设置注册中心url
        exporter.setRegisterUrl(registeredProviderUrl);
        // 设置override数据订阅的url
        exporter.setSubscribeUrl(overrideSubscribeUrl);
        // 创建并返回 DestroyableExporter
        //Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<>(exporter);
    }

    private URL overrideUrlWithConfig(URL providerUrl, OverrideListener listener) {
        providerUrl = providerConfigurationListener.overrideUrl(providerUrl);
        ServiceConfigurationListener serviceConfigurationListener = new ServiceConfigurationListener(providerUrl, listener);
        serviceConfigurationListeners.put(providerUrl.getServiceKey(), serviceConfigurationListener);
        return serviceConfigurationListener.overrideUrl(providerUrl);
    }

    //在这里根据不同的协议配置，调用不同的protocol实现。跟暴露到本地的时候实现InjvmProtocol一样
    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker, URL providerUrl) {
        //服务提供者url的string形式，并且去除dynamic参数
        String key = getCacheKey(originInvoker);

        // 加入缓存
        return (ExporterChangeableWrapper<T>) bounds.computeIfAbsent(key, s -> {
            // 创建 Invoker 为委托类对象
            Invoker<?> invokerDelegate = new InvokerDelegate<>(originInvoker, providerUrl);
            // 调用 protocol 的 export 方法暴露服务
            return new ExporterChangeableWrapper<>((Exporter<T>) protocol.export(invokerDelegate), originInvoker);
        });
    }

    public <T> void reExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        // update local exporter
        ExporterChangeableWrapper exporter = doChangeLocalExport(originInvoker, newInvokerUrl);
        // update registry
        URL registryUrl = getRegistryUrl(originInvoker);
        final URL registeredProviderUrl = getRegisteredProviderUrl(newInvokerUrl, registryUrl);

        //decide if we need to re-publish
        ProviderInvokerWrapper<T> providerInvokerWrapper = ProviderConsumerRegTable.getProviderWrapper(registeredProviderUrl, originInvoker);
        ProviderInvokerWrapper<T> newProviderInvokerWrapper = ProviderConsumerRegTable.registerProvider(originInvoker, registryUrl, registeredProviderUrl);
        /**
         * Only if the new url going to Registry is different with the previous one should we do unregister and register.
         */
        if (providerInvokerWrapper.isReg() && !registeredProviderUrl.equals(providerInvokerWrapper.getProviderUrl())) {
            unregister(registryUrl, providerInvokerWrapper.getProviderUrl());
            register(registryUrl, registeredProviderUrl);
            newProviderInvokerWrapper.setReg(true);
        }

        exporter.setRegisterUrl(registeredProviderUrl);
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
        } else {
            final Invoker<T> invokerDelegate = new InvokerDelegate<T>(originInvoker, newInvokerUrl);
            exporter.setExporter(protocol.export(invokerDelegate));
        }
        return exporter;
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    private Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        return registryFactory.getRegistry(registryUrl);
    }

    private URL getRegistryUrl(Invoker<?> originInvoker) {
        URL registryUrl = originInvoker.getUrl();
        if (REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            String protocol = registryUrl.getParameter(REGISTRY_KEY, DEFAULT_DIRECTORY);
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(REGISTRY_KEY);
        }
        return registryUrl;
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param providerUrl
     * @return url to registry.
     */
    private URL getRegisteredProviderUrl(final URL providerUrl, final URL registryUrl) {
        //The address you see at the registry
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) {
            return providerUrl.removeParameters(getFilteredKeys(providerUrl)).removeParameters(
                    MONITOR_KEY, BIND_IP_KEY, BIND_PORT_KEY, QOS_ENABLE, QOS_PORT, ACCEPT_FOREIGN_IP, VALIDATION_KEY,
                    INTERFACES);
        } else {
            String extra_keys = registryUrl.getParameter(EXTRA_KEYS_KEY, "");
            // if path is not the same as interface name then we should keep INTERFACE_KEY,
            // otherwise, the registry structure of zookeeper would be '/dubbo/path/providers',
            // but what we expect is '/dubbo/interface/providers'
            if (!providerUrl.getPath().equals(providerUrl.getParameter(Constants.INTERFACE_KEY))) {
                if (StringUtils.isNotEmpty(extra_keys)) {
                    extra_keys += ",";
                }
                extra_keys += Constants.INTERFACE_KEY;
            }
            String[] paramsToRegistry = getParamsToRegistry(DEFAULT_REGISTER_PROVIDER_KEYS
                    , Constants.COMMA_SPLIT_PATTERN.split(extra_keys));
            return URL.valueOf(providerUrl, paramsToRegistry, providerUrl.getParameter(METHODS_KEY, (String[]) null));
        }

    }

    //设置配置中心url，协议为provider,category为configurators
    private URL getSubscribedOverrideUrl(URL registeredProviderUrl) {
        return registeredProviderUrl.setProtocol(PROVIDER_PROTOCOL)
                .addParameters(CATEGORY_KEY, CONFIGURATORS_CATEGORY, CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     * 获取服务提供者的url
     * @param originInvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> originInvoker) {
        String export = originInvoker.getUrl().getParameterAndDecoded(EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + originInvoker.getUrl());
        }
        return URL.valueOf(export);
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    //服务提供者url的string形式，并且去除dynamic参数
    private String getCacheKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    //如果是注册服务中心，则直接创建代理。如果不是，先处理组配置，
    // 根据组配置来决定Cluster的实现方式，然后调用doRefer方法。
    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 取 registry 参数值，并将其设置为协议头，默认是dubbo
        url = URLBuilder.from(url)
                .setProtocol(url.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY))
                .removeParameter(REGISTRY_KEY)
                .build();
        // 获得注册中心实例
        Registry registry = registryFactory.getRegistry(url);
        // 如果是注册中心服务，则返回注册中心服务的invoker
        if (RegistryService.class.equals(type)) {
            // 如果是注册中心服务，则返回注册中心服务的invoker
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        // 将 url 查询字符串转为 Map
        // group="a,b" or group="*"
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        // 获得group值
        String group = qs.get(Constants.GROUP_KEY);
        if (group != null && group.length() > 0) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {
                //如果group多于一个，则使用分组聚合的cluster
                return doRefer(getMergeableCluster(), registry, type, url);
            }
        }
        // 只有一个组或者没有组配置，则直接执行doRefer
        return doRefer(cluster, registry, type, url);
    }

    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }

    /*
    1.创建一个 RegistryDirectory 实例，然后生成服务者消费者链接。
    2.向注册中心进行注册。
    3.紧接着订阅 providers、configurators、routers 等节点下的数据。完成订阅后，
    RegistryDirectory 会收到这几个节点下的子节点信息。
    4.由于一个服务可能部署在多台服务器上，这样就会在 providers 产生多个节点，
    这个时候就需要 Cluster 将多个服务节点合并为一个，并生成一个 Invoker。
     */
    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        // 创建 RegistryDirectory 实例
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        // 设置注册中心
        directory.setRegistry(registry);
        // 设置协议
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        // 所有属性放到map中
        Map<String, String> parameters = new HashMap<String, String>(directory.getUrl().getParameters());
        // 生成服务消费者链接
        URL subscribeUrl = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);
        // 注册服务消费者，在 consumers 目录下新节点
        if (!ANY_VALUE.equals(url.getServiceInterface()) && url.getParameter(REGISTER_KEY, true)) {
            directory.setRegisteredConsumerUrl(getRegisteredConsumerUrl(subscribeUrl, url));
            // 注册服务消费者
            registry.register(directory.getRegisteredConsumerUrl());
        }
        // 创建路由规则链
        directory.buildRouterChain(subscribeUrl);
        // 订阅 注册中心中的providers、configurators、routers 等节点数据
        directory.subscribe(subscribeUrl.addParameter(CATEGORY_KEY,
                PROVIDERS_CATEGORY + "," + CONFIGURATORS_CATEGORY + "," + ROUTERS_CATEGORY));
        // 一个注册中心可能有多个服务提供者，因此这里需要将多个服务提供者合并为一个，生成一个invoker
        Invoker invoker = cluster.join(directory);
        // 记录服务消费者
        ProviderConsumerRegTable.registerConsumer(invoker, url, subscribeUrl, directory);
        return invoker;
    }

    public URL getRegisteredConsumerUrl(final URL consumerUrl, URL registryUrl) {
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) {
            return consumerUrl.addParameters(CATEGORY_KEY, CONSUMERS_CATEGORY,
                    CHECK_KEY, String.valueOf(false));
        } else {
            return URL.valueOf(consumerUrl, DEFAULT_REGISTER_CONSUMER_KEYS, null).addParameters(
                    CATEGORY_KEY, CONSUMERS_CATEGORY, CHECK_KEY, String.valueOf(false));
        }
    }

    // available to test
    public String[] getParamsToRegistry(String[] defaultKeys, String[] additionalParameterKeys) {
        int additionalLen = additionalParameterKeys.length;
        String[] registryParams = new String[defaultKeys.length + additionalLen];
        System.arraycopy(defaultKeys, 0, registryParams, 0, defaultKeys.length);
        System.arraycopy(additionalParameterKeys, 0, registryParams, defaultKeys.length, additionalLen);
        return registryParams;
    }

    @Override
    public void destroy() {
        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();

        DynamicConfiguration.getDynamicConfiguration()
                .removeListener(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX, providerConfigurationListener);
    }

    //Merge the urls of configurators
    private static URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
        if (configurators != null && configurators.size() > 0) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    public static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegate(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegate) {
                return ((InvokerDelegate<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    static private class DestroyableExporter<T> implements Exporter<T> {

        private Exporter<T> exporter;

        public DestroyableExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            exporter.unexport();
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    //配置监听器
    private class OverrideListener implements NotifyListener {
        private final URL subscribeUrl;
        private final Invoker originInvoker;


        private List<Configurator> configurators;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information, is always not empty, The meaning is the same as the
         *             return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            logger.debug("original override urls: " + urls);

            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl.addParameter(CATEGORY_KEY,
                    CONFIGURATORS_CATEGORY));
            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);

            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            this.configurators = Configurator.toConfigurators(classifyUrls(matchedUrls, UrlUtils::isConfigurator))
                    .orElse(configurators);

            doOverrideIfNecessary();
        }

        public synchronized void doOverrideIfNecessary() {
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegate) {
                invoker = ((InvokerDelegate<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();
            //Merged with this configuration
            URL newUrl = getConfigedInvokerUrl(configurators, originUrl);
            newUrl = getConfigedInvokerUrl(serviceConfigurationListeners.get(originUrl.getServiceKey())
                    .getConfigurators(), newUrl);
            newUrl = getConfigedInvokerUrl(providerConfigurationListener.getConfigurators(), newUrl);
            if (!currentUrl.equals(newUrl)) {
                RegistryProtocol.this.reExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl +
                        ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(CATEGORY_KEY) == null && OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    //兼容老版本
                    overrideUrl = url.addParameter(CATEGORY_KEY, CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    //是否是同一个服务的配置
                    result.add(url);
                }
            }
            return result;
        }
    }

    private class ServiceConfigurationListener extends AbstractConfiguratorListener {
        private URL providerUrl;
        private OverrideListener notifyListener;

        public ServiceConfigurationListener(URL providerUrl, OverrideListener notifyListener) {
            this.providerUrl = providerUrl;
            this.notifyListener = notifyListener;
            this.initWith(providerUrl.getEncodedServiceKey() + CONFIGURATORS_SUFFIX);
        }

        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            notifyListener.doOverrideIfNecessary();
        }
    }

    private class ProviderConfigurationListener extends AbstractConfiguratorListener {

        public ProviderConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        /**
         * Get existing configuration rule and override provider url before exporting.
         *
         * @param providerUrl
         * @param <T>
         * @return
         */
        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            overrideListeners.values().forEach(listener -> ((OverrideListener) listener).doOverrideIfNecessary());
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter
     * exported by the protocol, and can modify the relationship at the time of override.
     * exporter代理，在返回的invoker和协议导出的exporter之间建立对应关系，并可以在覆盖时修改该关系。
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final ExecutorService executor = newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;
        private URL subscribeUrl;
        private URL registerUrl;
        //此处的exporter已经是 协议导出的了，比如dubbo协议、http协议
        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);

            Registry registry = RegistryProtocol.INSTANCE.getRegistry(originInvoker);
            try {
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                NotifyListener listener = RegistryProtocol.INSTANCE.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
                DynamicConfiguration.getDynamicConfiguration()
                        .removeListener(subscribeUrl.getServiceKey() + CONFIGURATORS_SUFFIX,
                                serviceConfigurationListeners.get(subscribeUrl.getServiceKey()));
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(() -> {
                try {
                    int timeout = ConfigurationUtils.getServerShutdownTimeout();
                    if (timeout > 0) {
                        logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. " +
                                "Usually, this is called when you use dubbo API");
                        Thread.sleep(timeout);
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            });
        }

        public void setSubscribeUrl(URL subscribeUrl) {
            this.subscribeUrl = subscribeUrl;
        }

        public void setRegisterUrl(URL registerUrl) {
            this.registerUrl = registerUrl;
        }
    }
}
