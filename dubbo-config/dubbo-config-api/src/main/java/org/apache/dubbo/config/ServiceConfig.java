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
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.Constants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implemenation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wraps two
     * layers, and eventually will get a <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    /**
     * The urls of the services exported
     */
    //记录已经暴露的服务url(包括scope为none，什么都不做的，包括使用 injvm的)
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The exported services
     */
    //已经发布的服务(不包括scope为none，什么都不做的)（包括由于没有配置注册中心，因此没有暴露到注册中心的）
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    /**
     * The interface name of the exported service
     */
    private String interfaceName;

    /**
     * The interface class of the exported service
     */
    private Class<?> interfaceClass;

    /**
     * The reference of the interface implementation
     */
    private T ref;

    /**
     * The service name
     */
    //服务名(如果没有设置，则默认值使用 接口权限定类名)
    private String path;

    /**
     * The method configuration
     */
    private List<MethodConfig> methods;

    /**
     * The provider configuration
     */
    private ProviderConfig provider;

    /**
     * The providerIds
     */
    private String providerIds;

    /**
     * Whether the provider has been exported
     */
    //是否已经export
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    //是否 服务被取消导出了
    private transient volatile boolean unexported;

    /**
     * whether it is a GenericService
     */
    //是否是 泛化实现类
    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
        setMethods(MethodConfig.constructMethodConfig(service.methods()));
    }

    @Deprecated
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (CollectionUtils.isEmpty(providers)) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (CollectionUtils.isEmpty(protocols)) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    //该方法中是对各类配置的校验，并检查补全相关配置
    public void checkAndUpdateSubConfigs() {
        // 用于检测 provider、application 等核心配置类对象是否为空，
        // 若为空，则尝试从其他配置类对象中获取相应的实例。
        // Use default configs defined explicitly on global configs
        //ProviderConfig > ModuleConfig > ApplicationConfig，同时完善（application、module、registries、monitor、protocols、configCenter）这些配置
        completeCompoundConfigs();
        // 开启配置中心
        // Config Center should always being started first.
        startConfigCenter();
        // 检测 provider 是否为空，为空获取默认的provider，如果默认的provider也没有则新建一个默认的并设置到配置管理器中，并通过配置为其初始化
        checkDefault();
        // 1.检测 applicationConfig 是否为空，为空获取默认的applicationConfig，如果默认的applicationConfig也没有则新建一个默认的，并通过配置为其初始化
        // 2.设置application到配置管理器和ApplicationModel 中
        // 3.从配置文件或者系统变量中获取 服务端启动的服务器的关闭的等待时间 并设置到系统变量中
        checkApplication();
        //1.构建 RegistryConfig列表
        //直接能获取地址：从系统变量或者配置文件中 获取 dubbo.registry.address参数值
        //通过 registryIds：registryIds{spring配置文件} >registryIds{外部配置中前缀为dubbo.registries.的key并收集 子字符串（从 prefix+1-> 第一个.为止）} > 配置管理其中的默认registryConfig
        //2.如果没有显示配置 config-center，则使用 第一个zk RegistryConfig中的地址作为 外部配置的 zk地址，并更新配置
        checkRegistry();
        //1.构建 ProtocolConfig列表
        //从 对应的provider中获取 protocol
        //通过 protocolIds：protocolIds{spring配置文件} >protocolIds(ProtocolConfig的前缀就是dubbo.protocols.){外部配置中前缀为dubbo.protocols.的key并收集 子字符串（从 prefix+1-> 第一个.为止）} > 配置管理其中的默认protocolConfig
        checkProtocol();
        // 从配置中心获取相应配置并更新ServiceConfig的相关字段
        this.refresh();
        // 核对元数据中心配置是否为空
        checkMetadataReport();

        // 服务接口名不能为空，否则抛出异常
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        // 检测 ref 是否为泛化实现
        if (ref instanceof GenericService) {
            // 设置interfaceClass为GenericService
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                // 设置generic = true
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                // 非泛化实现：获得接口类型
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 对 interfaceClass，以及 <dubbo:method> 标签中的必要字段进行检查
            checkInterfaceAndMethods(interfaceClass, methods);
            // 对 ref 合法性进行检测
            checkRef();
            generic = Boolean.FALSE.toString(); // 设置 generic = "false",表示不是泛化实现
        }
        // stub local一样都是配置本地存根
        if (local != null) {
            if ("true".equals(local)) {
                local = interfaceName + "Local"; //本地存根类名
            }
            Class<?> localClass;
            try {
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) { //检查合法性
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub"; //本地存根类名
            }
            Class<?> stubClass;
            try {
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub); //本地存根合法性
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        // 本地存根合法性校验
        checkStubAndLocal(interfaceClass);
        // mock合法性校验
        // TODO: 发布侧需要校验mock？  by 15258 2019/6/3 22:25
        checkMock(interfaceClass);
    }

    //首先做的就是对配置的检查和更新，执行的是ServiceConfig
    // 中的checkAndUpdateSubConfigs方法。然后检测是否应该暴露，
    // 如果不应该暴露，则直接结束，然后检测是否配置了延迟加载，
    // 如果是，则使用定时器来实现延迟加载的目的。
    public synchronized void export() {
        //检查并且补全相关配置
        checkAndUpdateSubConfigs();

        // 如果不应该暴露，则直接结束
        if (!shouldExport()) {
            return;
        }

        // 如果使用延迟加载，则延迟delay时间后暴露服务
        if (shouldDelay()) {
            delayExportExecutor.schedule(this::doExport, delay, TimeUnit.MILLISECONDS);
        } else {
            // 暴露服务
            doExport();
        }
    }

    //// 是否允许暴露
    //返回export属性 ->provider.getExport
    private boolean shouldExport() {
        Boolean shouldExport = getExport();
        if (shouldExport == null && provider != null) {
            //如果没有设置，则获取provider的 是否允许暴露 的配置
            shouldExport = provider.getExport();
        }

        //默认值是 true
        // default value is true
        if (shouldExport == null) {
            return true;
        }

        return shouldExport;
    }

    //根据条件决定是否导出服务
    // 本身delay配置 -> provider.getDelay()
    private boolean shouldDelay() {
        // 获取 delay
        Integer delay = getDelay();
        if (delay == null && provider != null) {
            // 如果前面获取的 delay 为空，这里继续获取
            delay = provider.getDelay();
        }
        return delay != null && delay > 0;
    }

    //对于服务是否暴露在一次校验，然后会执行ServiceConfig的doExportUrls()方法，对于多协议多注册中心暴露服务进行支持。
    protected synchronized void doExport() {
        // 如果调用了取消暴露的方法，则unexported值为true
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        // 如果服务已经暴露了，则直接结束
        if (exported) {
            return;
        }
        // 设置已经暴露
        exported = true;

        //服务名，如果没有设置，则使用interfaceName
        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        // 多协议多注册中心暴露服务
        doExportUrls();
    }

    private void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        //如果不是出于导出状态
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occured when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    /*
   1. loadRegistries()方法是加载注册中心链接。
   2.服务的唯一性是通过以contextPath、path、group、version一起确定的。
   3.doExportUrlsFor1Protocol()方法开始组装URL。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        // 加载注册中心链接
        List<URL> registryURLs = loadRegistries(true);
        // 遍历 protocols，并在每个协议下暴露服务

        for (ProtocolConfig protocolConfig : protocols) {
            // 以contextPath、path、group、version来作为服务唯一性确定的key [group/][暴露协议扩展对应的contextPath/]path[:version]
            String pathKey = URL.buildKey(getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), group, version);
            ProviderModel providerModel = new ProviderModel(pathKey, ref, interfaceClass);
            ApplicationModel.initProviderModel(pathKey, providerModel);
            // 组装 URL
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    /*
    dubbo内部用URL来携带各类配置，贯穿整个调用链，它就是配置的载体。
    服务的配置被组装到URL中就是从这里开始，上面提到遍历每个协议配置，
    在每个协议下都暴露服务，就会执行ServiceConfig
    的doExportUrlsFor1Protocol()方法，该方法前半部分实现了
    组装URL的逻辑，
    后半部分实现了暴露dubbo服务等逻辑


    1.它把metrics、application、module、provider、protocol等所有配置都放入map中，
    2.针对method都配置，先做签名校验，先找到该服务是否有配置的方法存在，然后该方法签名是否有这个参数存在，都核对成功才将method的配置加入map。
    3.将泛化调用、版本号、method或者methods、token等信息加入map
    4.获得服务暴露地址和端口号，利用map内数据组装成URL。
     */
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        //// 获取协议名
        String name = protocolConfig.getName();
        if (StringUtils.isEmpty(name)) {
            // 如果为空，则是默认的dubbo
            name = Constants.DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();
        // 设置服务提供者侧
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);

        // 添加 协议版本、发布版本，时间戳 等信息到 map 中
        appendRuntimeParameters(map);
        // 添加metrics、application、module、provider、protocol的所有信息到map
        appendParameters(map, metrics);
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, provider, Constants.DEFAULT_KEY);
        appendParameters(map, protocolConfig);
        appendParameters(map, this);
        // 如果method的配置列表不为空
        if (CollectionUtils.isNotEmpty(methods)) {
            // 遍历method配置列表
            for (MethodConfig method : methods) {
                // 添加 MethodConfig 对象的字段信息到 map 中，键 = 方法名.属性名。
                // 把方法配置中标注了parameter的参数加入map中，方法名为前缀
                appendParameters(map, method, method.getName());
                // 比如存储 <dubbo:method name="sayHello" retries="2"> 对应的 MethodConfig，
                // 键 = sayHello.retries，map = {"sayHello.retries": 2, "xxx": "yyy"}
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                // 获得ArgumentConfig列表(专门用于 参数回调 这个功能)
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    //arguments中只包含了一个属性 {方法名}.{参数index}.callback -> true/false
                    // 遍历ArgumentConfig列表
                    for (ArgumentConfig argument : arguments) {
                        // // 检测 type 属性是否为空，或者空串
                        // convert argument type
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            // 利用反射获取该服务的所有方法集合
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods != null && methods.length > 0) {
                                // 遍历所有方法
                                for (int i = 0; i < methods.length; i++) {
                                    // 获得方法名
                                    String methodName = methods[i].getName();
                                    // 找到目标方法
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        // 通过反射获取目标方法的参数类型数组 argtypes
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            // 如果下标不为-1
                                            // 检测 argType 的名称与 ArgumentConfig 中的 type 属性是否一致
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                //  添加 ArgumentConfig 字段信息到 map 中
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                // 不一致，则抛出异常
                                                throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            //如果索引为-1
                                            // 遍历参数类型数组 argtypes，查找 argument.type 类型的参数
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    // 如果找到，则添加 ArgumentConfig 字段信息到 map 中
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            // 用户未配置 type 属性，但配置了 index 属性，且 index != -1，则直接添加到map
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            // 抛出异常
                            throw new IllegalArgumentException("Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        // 如果是泛化调用，则在map中设置generic和methods
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(Constants.GENERIC_KEY, generic);
            map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
        } else {
            // 获得 jar包修订版本
            String revision = Version.getVersion(interfaceClass, version);
            // 放入map
            if (revision != null && revision.length() > 0) {
                map.put(Constants.REVISION_KEY, revision);
            }

            // TODO: 为什么这边要包装？  by 15258 2019/6/4 7:52
            // 获得方法集合
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            // 如果为空，则告警
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                // 设置method为*
                map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
            } else {
                // 否则加入方法集合
                map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        //// 把token 的值加入到map中
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(Constants.TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(Constants.TOKEN_KEY, token);
            }
        }
        // 获取服务提供者用来绑定的IP地址并设置到 查询参数中，key: bind.ip,并返回 用来注册到注册中心的ip地址
        // export service
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        // 获取服务提供者用来绑定的端口号并设置到 查询参数中，key: bind.port,并返回 用来注册到注册中心的port
        Integer port = this.findConfigedPorts(protocolConfig, name, map);
        // 生成　URL
        // {暴露协议的扩展名}://{需要注册到注册中心的ip地址}:{需要注册到注册中心的端口}/[协议的contextPath/]path(默认是接口权限定类名)
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);

        // —————————————————————————————————————开始发布———————————————————————————————————————
        // 覆盖规则
        // TODO: 发布的使用有协议为 absent或者override的？那如果没有的话，都不需要覆盖了？  by 15258 2019/6/4 7:59
        // 加载 ConfiguratorFactory，并生成 Configurator 实例，判断是否有该协议的实现存在
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            //如果 协议名 为 override或者absert
            //根据url中的查询参数 改写，todo 这个地方能改写 url协议么？
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(Constants.SCOPE_KEY);
        // don't export when none is configured
        // // 如果 scope = none，则什么都不做
        if (!Constants.SCOPE_NONE.equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            // // scope != remote，暴露到本地
            if (!Constants.SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                exportLocal(url);
            }
            // scope != local，导出到远程
            // export to remote if the config is not local (export to local only when config is local)
            if (!Constants.SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                if (CollectionUtils.isNotEmpty(registryURLs)) {
                    // 如果注册中心链接集合不为空,// zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F172.17.48.52%3A20880%2Fcom.alibaba.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider
                    for (URL registryURL : registryURLs) {
                        // 遍历注册中心
                        // 添加dynamic配置
                        url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
                        // 加载监视器链接
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            // 添加监视器配置
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }

                        // 获得代理方式
                        // For providers, this is used to enable custom proxy to generate invoker
                        String proxy = url.getParameter(Constants.PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            // 添加代理方式到注册中心到url
                            registryURL = registryURL.addParameter(Constants.PROXY_KEY, proxy);
                        }

                        // 为服务提供类(ref)生成 Invoker
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
                        // DelegateProviderMetaDataInvoker 用于持有 Invoker 和 ServiceConfig
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                        // 暴露服务，并且生成Exporter
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        // 加入到暴露者集合中
                        exporters.add(exporter);
                    }
                } else {
                    // 仅仅暴露服务，不会记录暴露到地址
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    //直接暴露服务
                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    exporters.add(exporter);
                }
                /**
                 * @since 2.7.0
                 * ServiceData Store
                 */
                MetadataReportService metadataReportService = null;
                //// 如果元数据中心服务不为空，则发布该服务，也就是在元数据中心记录url中到部分配置
                if ((metadataReportService = getMetadataReportService()) != null) {
                    metadataReportService.publishProvider(url);
                }
            }
        }
        this.urls.add(url);
    }

    //导出本地执行的是ServiceConfig中的exportLocal()方法。
    //本地暴露调用的是injvm协议方法，也就是InjvmProtocol 的 export()方法
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        // 如果 URL 暴露的协议是 injvm，说明导出到远程就是导出到本地了，因此在导出到远程的时候再执行 导出到jvm的操作
        // todo 其实，老版本 这个地方根本没有做判断拦截
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            // 生成本地的url,分别把协议改为injvm，更新url injvm://127.0.0.1:0//[协议的contextPath/]path(默认是接口权限定类名)？ServiceConfig组装出来的其他查询参数
            URL local = URLBuilder.from(url)
                    .setProtocol(Constants.LOCAL_PROTOCOL)
                    .setHost(LOCALHOST_VALUE)
                    .setPort(0)
                    .build();
            // 通过代理工程创建invoker
            // 再调用export方法进行暴露服务，生成Exporter
            Exporter<?> exporter = protocol.export(
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
            // 把生成的暴露者加入集合
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    //获取 contextPath，本身配置 > provider配置
    private Optional<String> getContextPath(ProtocolConfig protocolConfig) {
        String contextPath = protocolConfig.getContextpath();
        if (StringUtils.isEmpty(contextPath) && provider != null) {
            contextPath = provider.getContextpath();
        }
        return Optional.ofNullable(contextPath);
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    // 获取服务提供者用来绑定的IP地址并设置到 查询参数中，key: bind.ip,并返回 用来注册到注册中心的ip地址
    // key优先级： {暴露服务协议对应的扩展名的大写形式}_{key} >{key}
    // 配置优先级：环境变量-> Java系统属性-> 从本机网卡 获取可供暴露到公网的ip地址
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            //protocolConfig中指定的 主机ip
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                // provider中指定的主机 ip
                hostToBind = provider.getHost();
            }

            if (StringUtils.isEmpty(hostToBind)) {
                anyhost = true;
                // 获取 本机一个可以暴露到公网的ip地址，如果实在没有，则使用127.0.0.1
                hostToBind = getLocalHost();

                if (StringUtils.isEmpty(hostToBind)) {
                    //其实根本不会走到这一步(通过 socket依次连接 注册中心，如果能连接成功，则获取 socket.getLocalAddress().getHostAddress())
                    hostToBind = findHostToBindByConnectRegistries(registryURLs);
                }
            }
        }

        map.put(Constants.BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        //
        String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }

    //通过 socket依次连接 注册中心，如果能连接成功，则获取 socket.getLocalAddress().getHostAddress()
    private String findHostToBindByConnectRegistries(List<URL> registryURLs) {
        if (CollectionUtils.isNotEmpty(registryURLs)) {
            for (URL registryURL : registryURLs) {
                if (Constants.MULTICAST.equalsIgnoreCase(registryURL.getParameter(Constants.REGISTRY_KEY))) {
                    // skip multicast registry since we cannot connect to it via Socket
                    continue;
                }
                try (Socket socket = new Socket()) {
                    SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                    socket.connect(addr, 1000);
                    return socket.getLocalAddress().getHostAddress();
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        return null;
    }

    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    // 获取服务提供者用来绑定的端口号并设置到 查询参数中，key: bind.port,并返回 用来注册到注册中心的port
    // key优先级： {暴露服务协议对应的扩展名的大写形式}_{key} >{key}
    // 配置优先级：环境变量-> 系统变量 -> 协议对应扩展的默认端口 -> 随机可用端口
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            // 从 本身配置> Provider配置中 获取ip
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            //从 协议扩展中获取 默认ip
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            // 获取随机可用端口
            if (portToBind == null || portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // save bind port, used as url's key later
        map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    /**
     * 从 环境变量和系统变量中 获取对应配置
     * 配置优先级：环境变量>系统变量
     * key优先级： {暴露服务协议对应的扩展名的大写形式}_{key} >{key}
     * @param protocolConfig
     * @param key
     * @return
     */
    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(port)) {
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    //完善组件配置
    // ProviderConfig（application、module、registries、monitor、protocols、configCenter） > ModuleConfig（registries，monitor） > ApplicationConfig（registries，monitor）
    private void completeCompoundConfigs() {
        if (provider != null) {
            if (application == null) {
                setApplication(provider.getApplication());
            }
            if (module == null) {
                setModule(provider.getModule());
            }
            if (registries == null) {
                setRegistries(provider.getRegistries());
            }
            if (monitor == null) {
                setMonitor(provider.getMonitor());
            }
            if (protocols == null) {
                setProtocols(provider.getProtocols());
            }
            if (configCenter == null) {
                setConfigCenter(provider.getConfigCenter());
            }
        }
        if (module != null) {
            if (registries == null) {
                setRegistries(module.getRegistries());
            }
            if (monitor == null) {
                setMonitor(module.getMonitor());
            }
        }
        if (application != null) {
            if (registries == null) {
                setRegistries(application.getRegistries());
            }
            if (monitor == null) {
                setMonitor(application.getMonitor());
            }
        }
    }

    // 检测 provider 是否为空，为空获取默认的provider，如果默认的provider也没有则新建一个默认的并设置到配置管理器中，并通过配置为其初始化
    private void checkDefault() {
        createProviderIfAbsent();
    }

    private void createProviderIfAbsent() {
        if (provider != null) {
            return;
        }
        setProvider (
                ConfigManager.getInstance()
                        .getDefaultProvider()
                        .orElseGet(() -> {
                            ProviderConfig providerConfig = new ProviderConfig();
                            providerConfig.refresh();
                            return providerConfig;
                        })
        );
    }

    //1.构建 ProtocolConfig列表
    //从 对应的provider中获取 protocol
    //通过 protocolIds：protocolIds{spring配置文件} >protocolIds(ProtocolConfig的前缀就是dubbo.protocols.){外部配置中前缀为dubbo.protocols.的key并收集 子字符串（从 prefix+1-> 第一个.为止）} > 配置管理其中的默认protocolConfig
    private void checkProtocol() {
        //如果没有指定 protocols，则使用 对应的provider中的protocol
        if (CollectionUtils.isEmpty(protocols) && provider != null) {
            setProtocols(provider.getProtocols());
        }
        convertProtocolIdsToProtocols();
    }

    //通过 protocolIds：protocolIds{spring配置文件} >protocolIds(ProtocolConfig的前缀就是dubbo.protocols.){外部配置中前缀为dubbo.protocols.的key并收集 子字符串（从 prefix+1-> 第一个.为止）} > 配置管理其中的默认protocolConfig
    private void convertProtocolIdsToProtocols() {
        if (StringUtils.isEmpty(protocolIds) && CollectionUtils.isEmpty(protocols)) {
            List<String> configedProtocols = new ArrayList<>();
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getExternalConfigurationMap(), Constants.PROTOCOLS_SUFFIX));
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getAppExternalConfigurationMap(), Constants.PROTOCOLS_SUFFIX));

            protocolIds = String.join(",", configedProtocols);
        }

        if (StringUtils.isEmpty(protocolIds)) {
            if (CollectionUtils.isEmpty(protocols)) {
               setProtocols(
                       ConfigManager.getInstance().getDefaultProtocols()
                        .filter(CollectionUtils::isNotEmpty)
                        .orElseGet(() -> {
                            ProtocolConfig protocolConfig = new ProtocolConfig();
                            protocolConfig.refresh();
                            return new ArrayList<>(Arrays.asList(protocolConfig));
                        })
               );
            }
        } else {
            String[] arr = Constants.COMMA_SPLIT_PATTERN.split(protocolIds);
            List<ProtocolConfig> tmpProtocols = CollectionUtils.isNotEmpty(protocols) ? protocols : new ArrayList<>();
            Arrays.stream(arr).forEach(id -> {
                if (tmpProtocols.stream().noneMatch(prot -> prot.getId().equals(id))) {
                    tmpProtocols.add(ConfigManager.getInstance().getProtocol(id).orElseGet(() -> {
                        ProtocolConfig protocolConfig = new ProtocolConfig();
                        protocolConfig.setId(id);
                        protocolConfig.refresh();
                        return protocolConfig;
                    }));
                }
            });
            if (tmpProtocols.size() > arr.length) {
                throw new IllegalStateException("Too much protocols found, the protocols comply to this service are :" + protocolIds + " but got " + protocols
                        .size() + " registries!");
            }
            setProtocols(tmpProtocols);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (StringUtils.isEmpty(id)) {
            id = interfaceName;
        }
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName(Constants.PATH_KEY, path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        ConfigManager.getInstance().addProvider(provider);
        this.provider = provider;
    }

    @Parameter(excluded = true)
    public String getProviderIds() {
        return providerIds;
    }

    public void setProviderIds(String providerIds) {
        this.providerIds = providerIds;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    @Override
    public void setMock(Boolean mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    @Override
    public void setMock(String mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Override
    @Parameter(excluded = true)
    public String getPrefix() {
        return Constants.DUBBO + ".service." + interfaceName;
    }
}
