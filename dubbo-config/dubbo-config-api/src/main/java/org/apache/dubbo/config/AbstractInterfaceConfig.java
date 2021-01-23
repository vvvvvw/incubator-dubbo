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
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.support.Cluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;

/**
 * AbstractDefaultConfig
 *
 * @export
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long serialVersionUID = -1559314110797223229L;

    /**
     * Local impl class name for the service interface
     */
    //本地存根 ，interfaceClass+Local
    protected String local;

    /**
     * Local stub class name for the service interface
     */
    //本地存根，，interfaceClass+Stub
    protected String stub;

    /**
     * Service monitor
     */
    protected MonitorConfig monitor;

    /**
     * Strategies for generating dynamic agents，there are two strategies can be choosed: jdk and javassist
     */
    protected String proxy;

    /**
     * Cluster type
     */
    protected String cluster;

    /**
     * The {@link Filter} when the provider side exposed a service or the customer side references a remote service used,
     * if there are more than one, you can use commas to separate them
     */
    protected String filter;

    /**
     * The Listener when the provider side exposes a service or the customer side references a remote service used
     * if there are more than one, you can use commas to separate them
     */
    protected String listener;

    /**
     * The owner of the service providers
     */
    protected String owner;

    /**
     * Connection limits, 0 means shared connection, otherwise it defines the connections delegated to the current service
     */
    protected Integer connections;

    /**
     * The layer of service providers
     */
    protected String layer;

    /**
     * The application info
     */
    protected ApplicationConfig application;

    /**
     * The module info
     */
    protected ModuleConfig module;

    /**
     * Registry centers
     */
    //注册中心配置（list 说明注册中心可以有多个）
    protected List<RegistryConfig> registries;

    protected String registryIds;

    // connection events
    protected String onconnect;

    /**
     * Disconnection events
     */
    protected String ondisconnect;

    /**
     * The metrics configuration
     */
    protected MetricsConfig metrics;
    protected MetadataReportConfig metadataReportConfig;

    protected ConfigCenterConfig configCenter;

    // callback limits
    private Integer callbacks;
    // the scope for referring/exporting a service, if it's local, it means searching in current JVM only.
    private String scope;

    /**
     * Check whether the registry config is exists, and then conversion it to {@link RegistryConfig}
     */
    //1.构建 RegistryConfig列表
    //直接能获取地址：从系统变量或者配置文件中 获取 dubbo.registry.address参数值
    //通过 registryIds：registryIds{spring配置文件} >registryIds{外部配置中前缀为dubbo.registries.的key并收集 子字符串（从 prefix+1-> 第一个.为止）} > 配置管理其中的默认registryConfig
    //2.如果没有显示配置 config-center，则使用 第一个zk RegistryConfig中的地址作为 外部配置的 zk地址，并更新配置
    protected void checkRegistry() {
        // 从系统变量或者配置文件中 获取 dubbo.registry.address参数值 并 转换为 RegistryConfig列表(设置了address字段)
        loadRegistriesFromBackwardConfig();

        //todo 在正常配置中没有手动设置 restry
        //获取 registryConfig列表并构造RegistryConfig列表（只设置id字段），registryIds{spring配置文件} >registryIds(其实就是在外部配置中对应registry配置了属性的){外部配置中前缀为dubbo.registries.的key并收集 子字符串（从 prefix+1-> 第一个.为止）} > 配置管理其中的默认registryConfig
        convertRegistryIdsToRegistries();

        for (RegistryConfig registryConfig : registries) {
            if (!registryConfig.isValid()) {
                throw new IllegalStateException("No registry config found or it's not a valid config! " +
                        "The registry config is: " + registryConfig);
            }
        }

        useRegistryForConfigIfNecessary();
    }

    // 1.检测 applicationConfig 是否为空，为空获取默认的applicationConfig，如果默认的applicationConfig也没有则新建一个默认的，并通过配置为其初始化
    // 2.设置application到配置管理器和ApplicationModel 中
    // 3.从配置文件或者系统变量中获取 服务端启动的服务器的关闭的等待时间 并设置到系统变量中
    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // for backward compatibility
        createApplicationIfAbsent();

        //name参数不能为空
        if (!application.isValid()) {
            throw new IllegalStateException("No application config found or it's not a valid config! " +
                    "Please add <dubbo:application name=\"...\" /> to your spring config.");
        }

        //设置到ApplicationModel中
        ApplicationModel.setApplication(application.getName());

        // 向后兼容
        // backward compatibility
        //从系统变量和配置文件中获取 dubbo.service.shutdown.wait参数 并设置到系统变量中
        String wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(Constants.SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }
    }

    //检查是否配置了服务监听器的url，如果没有创建一个默认的
    protected void checkMonitor() {
        createMonitorIfAbsent();
        if (!monitor.isValid()) {
            logger.info("There's no valid monitor config found, if you want to open monitor statistics for Dubbo, " +
                    "please make sure your monitor is configured properly.");
        }
    }

    private void createMonitorIfAbsent() {
        if (this.monitor != null) {
            return;
        }
        ConfigManager configManager = ConfigManager.getInstance();
        setMonitor(
                configManager
                        .getMonitor()
                        .orElseGet(() -> {
                            MonitorConfig monitorConfig = new MonitorConfig();
                            monitorConfig.refresh();
                            return monitorConfig;
                        })
        );
    }

    protected void checkMetadataReport() {
        // TODO get from ConfigManager first, only create if absent.
        if (metadataReportConfig == null) {
            //如果没有元数据上报配置，则设置元数据上报配置
            setMetadataReportConfig(new MetadataReportConfig());
        }
        //更新配置
        metadataReportConfig.refresh();
        //地址是否有效
        if (!metadataReportConfig.isValid()) {
            logger.warn("There's no valid metadata config found, if you are using the simplified mode of registry url, " +
                    "please make sure you have a metadata address configured properly.");
        }
    }

    //获取外部配置
    void startConfigCenter() {
        if (configCenter == null) {
            ConfigManager.getInstance().getConfigCenter().ifPresent(cc -> this.configCenter = cc);
        }

        if (this.configCenter != null) {
            // TODO there may have duplicate refresh
            //使用 spring和配置文件中的 配置来初始化 configCenter
            this.configCenter.refresh();
            //获取 外部配置中心的 配置，并添加到 多级配置中
            prepareEnvironment();
        }
        //将 ApplicationConfig, MonitorConfig, ModuleConfig, ProtocolConfig, RegistryConfig, ProviderConfig, ConsumerConfig的配置都刷新一遍
        ConfigManager.getInstance().refreshAll();
    }

    //如果启用 configcenter，则从configcenter中获取 全局和应用配置 并 更新到 相应的内存配置中
    private void prepareEnvironment() {
        // 根据configcenter的address是否设定和设定是否有效来判断 configcenter是否启用
        if (configCenter.isValid()) {
            //如果已经初始化，直接返回
            if (!configCenter.checkOrUpdateInited()) {
                return;
            }
            DynamicConfiguration dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            // key:{configFile参数值(默认值是 dubbo.properties)} group:{group参数值(dubbo)}
            // 获取外部配置，如果是zk：/dubbo/config/dubbo/dubbo.properties(默认是)
            String configContent = dynamicConfiguration.getConfig(configCenter.getConfigFile(), configCenter.getGroup());

            //从 applicationConfig配置中获取应用名
            //此处的application 是在 ConfigCenterBean的构造函数中配置的
            //在外部化配置中，如果在 <dubbo:config-center />标签中没有指定 application参数，则会获取spring容器中 default为null或者true的 applicationConfig实例并设置到 application属性上，为之后的 应用特定的外部化配置 做准备
            String appGroup = application != null ? application.getName() : null;
            String appConfigContent = null;
            if (StringUtils.isNotEmpty(appGroup)) {
                //获取应用级别的配置
                // key:{appConfigFile参数值(默认值是 null)} > {configFile参数值(默认值是 dubbo.properties)}
                // group:{applicationConfig 默认为null}
                // zk默认值：/dubbo/config/{应用名}/dubbo.properties
                appConfigContent = dynamicConfiguration.getConfig
                        (StringUtils.isNotEmpty(configCenter.getAppConfigFile()) ? configCenter.getAppConfigFile() : configCenter.getConfigFile(),
                         appGroup
                        );
            }
            try {
                Environment.getInstance().setConfigCenterFirst(configCenter.isHighestPriority());
                //将外部配置 全部添加到 内存全局配置中
                Environment.getInstance().updateExternalConfigurationMap(parseProperties(configContent));
                //将外部配置 全部添加到 应用全局配置中
                Environment.getInstance().updateAppExternalConfigurationMap(parseProperties(appConfigContent));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
            }
        }
    }

    //获取对应外部配置类 扩展
    private DynamicConfiguration getDynamicConfiguration(URL url) {
        DynamicConfigurationFactory factories = ExtensionLoader
                .getExtensionLoader(DynamicConfigurationFactory.class)
                .getExtension(url.getProtocol());
        DynamicConfiguration configuration = factories.getDynamicConfiguration(url);
        Environment.getInstance().setDynamicConfiguration(configuration);
        return configuration;
    }

    /**
     *
     * Load the registry and conversion it to {@link URL}, the priority order is: system property > dubbo registry config
     *
     * @param provider whether it is the provider side
     * @return
     */
    //加载注册中心url(path为 RegistryService接口的全限定类名)
    protected List<URL> loadRegistries(boolean provider) {
        // check && override if necessary
        List<URL> registryList = new ArrayList<URL>();
        // 如果registries为空，直接返回空集合
        if (CollectionUtils.isNotEmpty(registries)) {
            // 遍历注册中心配置集合registries
            for (RegistryConfig config : registries) {
                // 获得地址
                String address = config.getAddress();
                if (StringUtils.isEmpty(address)) {
                    // 若地址为空，则设置为0.0.0.0
                    address = Constants.ANYHOST_VALUE;
                }
                // 如果地址为N/A，则跳过
                if (!RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                    Map<String, String> map = new HashMap<String, String>();
                    // 添加 ApplicationConfig 中的字段信息到 map 中
                    appendParameters(map, application);
                    // 添加 RegistryConfig 字段信息到 map 中
                    appendParameters(map, config);
                    // 添加path属性
                    map.put(Constants.PATH_KEY, RegistryService.class.getName());
                    // 添加 协议版本、发布版本，时间戳 等信息到 map 中
                    appendRuntimeParameters(map);
                    // 如果map中没有protocol，则默认为使用dubbo协议
                    if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                        map.put(Constants.PROTOCOL_KEY, Constants.DUBBO_PROTOCOL);
                    }
                    // 解析得到 URL 列表，address 可能包含多个注册中心 ip，因此解析得到的是一个 URL 列表
                    List<URL> urls = UrlUtils.parseURLs(address, map);

                    // 遍历URL 列表
                    for (URL url : urls) {
                        // 将 URL 协议头设置为 registry
                        url = URLBuilder.from(url)
                                //todo 注册中心使用的协议设置到参数中，在 registryProtocol中会拿出来
                                .addParameter(Constants.REGISTRY_KEY, url.getProtocol())
                                //todo 设置为 registry协议
                                .setProtocol(Constants.REGISTRY_PROTOCOL)
                                .build();
                        /*
                        // 通过判断条件，决定是否添加 url 到 registryList 中，条件如下：
                        // 如果是服务提供者，并且是register 这个key没有值或者为true(没有找到register 这个key设置的地方，应该是在address参数后面加上？来设置的)   或者   是消费者端，并且subscribe这个查询参数没有值或者为true
                        // 则加入到registryList
                         */
                        if ((provider && url.getParameter(Constants.REGISTER_KEY, true))
                                || (!provider && url.getParameter(Constants.SUBSCRIBE_KEY, true))) {
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        return registryList;
    }

    /**
     *
     * Load the monitor config from the system properties and conversation it to {@link URL}
     *
     * @param registryURL
     * @return
     */
    // 服务监听器的url
    protected URL loadMonitor(URL registryURL) {
        checkMonitor();
        Map<String, String> map = new HashMap<String, String>();
        map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
        appendRuntimeParameters(map);
        //set ip
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        }
        //设置用来注册到注册中心的url
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);
        //设置 monitor配置到map中
        appendParameters(map, monitor);
        //设置 applicaton配置到map中
        appendParameters(map, application);
        String address = monitor.getAddress();
        // 监听器url的地址
        String sysaddress = System.getProperty("dubbo.monitor.address");
        if (sysaddress != null && sysaddress.length() > 0) {
            address = sysaddress;
        }
        if (ConfigUtils.isNotEmpty(address)) {
            //如果配置了address地址
            if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                if (getExtensionLoader(MonitorFactory.class).hasExtension(Constants.LOGSTAT_PROTOCOL)) {
                    //如果没有配置 protocol并且有logstat扩展，则使用logstat扩展，否则使用dubbo
                    map.put(Constants.PROTOCOL_KEY, Constants.LOGSTAT_PROTOCOL);
                } else {
                    map.put(Constants.PROTOCOL_KEY, Constants.DUBBO_PROTOCOL);
                }
            }
            // ({protocol}|logstat|dubbo)://address?interface=org.apache.dubbo.monitor.MonitorService&register.ip={服务暴露的ip地址}
            return UrlUtils.parseURL(address, map);
        } else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
            // 如果是从注册中心中获取monitor地址，注册中心的相关地址和查询参数
            // dubbo://注册中心相关地址、参数和path，协议?protocol=registry&refer=monitorConfig+applicationConfig和其他参数
            return URLBuilder.from(registryURL)
                    .setProtocol(Constants.DUBBO_PROTOCOL)
                    .addParameter(Constants.PROTOCOL_KEY, Constants.REGISTRY_PROTOCOL)
                    .addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map))
                    .build();
        }
        return null;
    }

    static void appendRuntimeParameters(Map<String, String> map) {
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(Constants.RELEASE_KEY, Version.getVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
    }

    private URL loadMetadataReporterURL() {
        String address = metadataReportConfig.getAddress();
        if (StringUtils.isEmpty(address)) {
            return null;
        }
        Map<String, String> map = new HashMap<String, String>();
        appendParameters(map, metadataReportConfig);
        return UrlUtils.parseURL(address, map);
    }

    protected MetadataReportService getMetadataReportService() {

        if (metadataReportConfig == null || !metadataReportConfig.isValid()) {
            return null;
        }
        return MetadataReportService.instance(this::loadMetadataReporterURL);
    }

    /**
     * Check whether the remote service interface and the methods meet with Dubbo's requirements.it mainly check, if the
     * methods configured in the configuration file are included in the interface of remote service
     *
     * @param interfaceClass the interface of remote service
     * @param methods the methods configured
     */
    //检查 接口类，使用混合配置 更新 methodConfig的属性
    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        // interface cannot be null
        Assert.notNull(interfaceClass, new IllegalStateException("interface not allow null!"));

        // to verify interfaceClass is an interface
        if (!interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        // check if methods exist in the remote service interface
        if (CollectionUtils.isNotEmpty(methods)) {
            for (MethodConfig methodBean : methods) {
                methodBean.setService(interfaceClass.getName());
                methodBean.setServiceId(this.getId());
                //使用 混合配置中的配置 来 更新 methodConfig的属性
                methodBean.refresh();
                String methodName = methodBean.getName();
                if (StringUtils.isEmpty(methodName)) {
                    throw new IllegalStateException("<dubbo:method> name attribute is required! Please check: " +
                            "<dubbo:service interface=\"" + interfaceClass.getName() + "\" ... >" +
                            "<dubbo:method name=\"\" ... /></<dubbo:reference>");
                }

                boolean hasMethod = Arrays.stream(interfaceClass.getMethods()).anyMatch(method -> method.getName().equals(methodName));
                if (!hasMethod) {
                    throw new IllegalStateException("The interface " + interfaceClass.getName()
                            + " not found method " + methodName);
                }
            }
        }
    }

    /**
     * Legitimacy check and setup of local simulated operations. The operations can be a string with Simple operation or
     * a classname whose {@link Class} implements a particular function
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface that will be referenced
     */
    void checkMock(Class<?> interfaceClass) {
        if (ConfigUtils.isEmpty(mock)) {
            return;
        }

        String normalizedMock = MockInvoker.normalizeMock(mock); //正则化mock
        if (normalizedMock.startsWith(Constants.RETURN_PREFIX)) {
            normalizedMock = normalizedMock.substring(Constants.RETURN_PREFIX.length()).trim();
            try {
                //Check whether the mock value is legal, if it is illegal, throw exception
                MockInvoker.parseMockValue(normalizedMock);
            } catch (Exception e) {
                throw new IllegalStateException("Illegal mock return in <dubbo:service/reference ... " +
                        "mock=\"" + mock + "\" />");
            }
        } else if (normalizedMock.startsWith(Constants.THROW_PREFIX)) {
            normalizedMock = normalizedMock.substring(Constants.THROW_PREFIX.length()).trim();
            if (ConfigUtils.isNotEmpty(normalizedMock)) {
                try {
                    //Check whether the mock value is legal
                    MockInvoker.getThrowable(normalizedMock);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock throw in <dubbo:service/reference ... " +
                            "mock=\"" + mock + "\" />");
                }
            }
        } else {
            //Check whether the mock class is a implementation of the interfaceClass, and if it has a default constructor
            MockInvoker.getMockObject(normalizedMock, interfaceClass);
        }
    }

    /**
     * Legitimacy check of stub, note that: the local will deprecated, and replace with <code>stub</code>
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface
     */
    void checkStubAndLocal(Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(local)) {
            Class<?> localClass = ConfigUtils.isDefault(local) ?
                    ReflectUtils.forName(interfaceClass.getName() + "Local") : ReflectUtils.forName(local);
            verify(interfaceClass, localClass);
        }
        if (ConfigUtils.isNotEmpty(stub)) {
            Class<?> localClass = ConfigUtils.isDefault(stub) ?
                    ReflectUtils.forName(interfaceClass.getName() + "Stub") : ReflectUtils.forName(stub);
            verify(interfaceClass, localClass);
        }
    }

    private void verify(Class<?> interfaceClass, Class<?> localClass) {
        if (!interfaceClass.isAssignableFrom(localClass)) {
            throw new IllegalStateException("The local implementation class " + localClass.getName() +
                    " not implement interface " + interfaceClass.getName());
        }

        try {
            //Check if the localClass a constructor with parameter who's type is interfaceClass
            ReflectUtils.findConstructor(localClass, interfaceClass);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() +
                    "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
        }
    }

    //获取 registryConfig列表并构造RegistryConfig列表（只设置id字段），registryIds{spring配置文件} >registryIds{外部配置中前缀为dubbo.registries.的key并收集 子字符串（从 prefix+1-> 第一个.为止）} > 配置管理其中的默认registryConfig
    private void convertRegistryIdsToRegistries() {
        if (StringUtils.isEmpty(registryIds) && CollectionUtils.isEmpty(registries)) {
            //如果 registryIds和registries都没有配置，则从 外部全局配置和 应用外部配置 中 获取 key前缀为 dubbo.registries.的条目并 收集其valuie
            Set<String> configedRegistries = new HashSet<>();
            configedRegistries.addAll(getSubProperties(Environment.getInstance().getExternalConfigurationMap(),
                    Constants.REGISTRIES_SUFFIX));
            configedRegistries.addAll(getSubProperties(Environment.getInstance().getAppExternalConfigurationMap(),
                    Constants.REGISTRIES_SUFFIX));

            registryIds = String.join(Constants.COMMA_SEPARATOR, configedRegistries);
        }

        if (StringUtils.isEmpty(registryIds)) {
            if (CollectionUtils.isEmpty(registries)) {
                //如果 spring配置和 外部配置中 都不能获取到 registryId
                //则从配置管理器中 获取 默认 registries
                setRegistries(
                        ConfigManager.getInstance().getDefaultRegistries()
                        .filter(CollectionUtils::isNotEmpty)
                        .orElseGet(() -> {
                            RegistryConfig registryConfig = new RegistryConfig();
                            registryConfig.refresh();
                            return Arrays.asList(registryConfig);
                        })
                );
            }
        } else {
            // 使用 获取到的 registryIds 来构造 registryConfig列表
            String[] ids = Constants.COMMA_SPLIT_PATTERN.split(registryIds);
            List<RegistryConfig> tmpRegistries = CollectionUtils.isNotEmpty(registries) ? registries : new ArrayList<>();
            Arrays.stream(ids).forEach(id -> {
                if (tmpRegistries.stream().noneMatch(reg -> reg.getId().equals(id))) {
                    tmpRegistries.add(ConfigManager.getInstance().getRegistry(id).orElseGet(() -> {
                        RegistryConfig registryConfig = new RegistryConfig();
                        registryConfig.setId(id);
                        registryConfig.refresh();
                        return registryConfig;
                    }));
                }
            });

            if (tmpRegistries.size() > ids.length) {
                throw new IllegalStateException("Too much registries found, the registries assigned to this service " +
                        "are :" + registryIds + ", but got " + tmpRegistries.size() + " registries!");
            }

            setRegistries(tmpRegistries);
        }

    }

    // 从系统变量或者配置文件中 获取 dubbo.registry.address参数值 并 转换为 RegistryConfig列表
    // 其实 最终配置管理器中 应该只能被添加一个且key为default，因为new 出来的RegistryConfig都是 default RegistryConfig
    private void loadRegistriesFromBackwardConfig() {
        // for backward compatibility
        // -Ddubbo.registry.address is now deprecated.
        //如果没有配置registries
        if (registries == null || registries.isEmpty()) {
            //从系统变量或者 配置文件 获取 dubbo.registry.address这个属性值，使用|来分隔并 构造 RegistryConfig列表
            //将构造得到的 RegistryConfig列表 设置到配置管理器中
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                List<RegistryConfig> tmpRegistries = new ArrayList<RegistryConfig>();
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registryConfig.refresh();
                    tmpRegistries.add(registryConfig);
                }
                setRegistries(tmpRegistries);
            }
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center if the registry protocol is zookeeper and
     * there's no config center specified explicitly.
     */
    //为了兼容，如果没有显示配置 config-center，则使用 第一个zk RegistryConfig中的地址作为 外部配置的 zk地址，并更新配置
    private void useRegistryForConfigIfNecessary() {
        registries.stream().filter(RegistryConfig::isZookeeperProtocol).findFirst().ifPresent(rc -> {
            // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
            Environment.getInstance().getDynamicConfiguration().orElseGet(() -> {
                ConfigManager configManager = ConfigManager.getInstance();
                ConfigCenterConfig cc = configManager.getConfigCenter().orElse(new ConfigCenterConfig());
                cc.setProtocol(rc.getProtocol());
                cc.setAddress(rc.getAddress());
                cc.setHighestPriority(false);
                setConfigCenter(cc);
                startConfigCenter();
                return null;
            });
        });
    }

    /**
     * @return local
     * @deprecated Replace to <code>getStub()</code>
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(Boolean)</code>
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(local.toString());
        }
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(String)</code>
     */
    @Deprecated
    public void setLocal(String local) {
        checkName(Constants.LOCAL_KEY, local);
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(Boolean stub) {
        if (stub == null) {
            setStub((String) null);
        } else {
            setStub(stub.toString());
        }
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, Constants.CLUSTER_KEY, cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, Constants.PROXY_KEY, proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = Constants.REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, Constants.FILE_KEY, filter);
        this.filter = filter;
    }

    @Parameter(key = Constants.INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    public void setListener(String listener) {
        checkMultiExtension(InvokerListener.class, Constants.LISTENER_KEY, listener);
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasSymbol(Constants.LAYER_KEY, layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    public void setApplication(ApplicationConfig application) {
        ConfigManager.getInstance().setApplication(application);
        this.application = application;
    }

    private void createApplicationIfAbsent() {
        if (this.application != null) {
            return;
        }
        ConfigManager configManager = ConfigManager.getInstance();
        setApplication(
                configManager
                        .getApplication()
                        .orElseGet(() -> {
                            ApplicationConfig applicationConfig = new ApplicationConfig();
                            applicationConfig.refresh();
                            return applicationConfig;
                        })
        );
    }

    public ModuleConfig getModule() {
        return module;
    }

    public void setModule(ModuleConfig module) {
        ConfigManager.getInstance().setModule(module);
        this.module = module;
    }

    public RegistryConfig getRegistry() {
        return CollectionUtils.isEmpty(registries) ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        setRegistries(registries);
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({"unchecked"})
    public void setRegistries(List<? extends RegistryConfig> registries) {
        ConfigManager.getInstance().addRegistries((List<RegistryConfig>) registries);
        this.registries = (List<RegistryConfig>) registries;
    }

    @Parameter(excluded = true)
    public String getRegistryIds() {
        return registryIds;
    }

    public void setRegistryIds(String registryIds) {
        this.registryIds = registryIds;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(String monitor) {
        setMonitor(new MonitorConfig(monitor));
    }

    public void setMonitor(MonitorConfig monitor) {
        ConfigManager.getInstance().setMonitor(monitor);
        this.monitor = monitor;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        checkMultiName("owner", owner);
        this.owner = owner;
    }

    public ConfigCenterConfig getConfigCenter() {
        return configCenter;
    }

    public void setConfigCenter(ConfigCenterConfig configCenter) {
        ConfigManager.getInstance().setConfigCenter(configCenter);
        this.configCenter = configCenter;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public MetadataReportConfig getMetadataReportConfig() {
        return metadataReportConfig;
    }

    public void setMetadataReportConfig(MetadataReportConfig metadataReportConfig) {
        this.metadataReportConfig = metadataReportConfig;
    }

    public MetricsConfig getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsConfig metrics) {
        this.metrics = metrics;
    }
}
