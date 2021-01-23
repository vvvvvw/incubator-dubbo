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
package org.apache.dubbo.rpc.cluster.configurator.parser;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.cluster.configurator.parser.model.ConfigItem;
import org.apache.dubbo.rpc.cluster.configurator.parser.model.ConfiguratorConfig;

import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Config parser
 */
public class ConfigParser {

    // 将外部动态yml配置解析为 url
    public static List<URL> parseConfigurators(String rawConfig) {
        List<URL> urls = new ArrayList<>();
        // 将配置解析为 ConfiguratorConfig对象
        ConfiguratorConfig configuratorConfig = parseObject(rawConfig);

        String scope = configuratorConfig.getScope();
        List<ConfigItem> items = configuratorConfig.getConfigs();

        if (ConfiguratorConfig.SCOPE_APPLICATION.equals(scope)) {
            items.forEach(item -> urls.addAll(appItemToUrls(item, configuratorConfig)));
        } else {
            // service scope by default.
            items.forEach(item -> urls.addAll(serviceItemToUrls(item, configuratorConfig)));
        }
        return urls;
    }

    private static <T> T parseObject(String rawConfig) {
        Constructor constructor = new Constructor(ConfiguratorConfig.class);
        TypeDescription itemDescription = new TypeDescription(ConfiguratorConfig.class);
        itemDescription.addPropertyParameters("items", ConfigItem.class);
        constructor.addTypeDescription(itemDescription);

        Yaml yaml = new Yaml(constructor);
        return yaml.load(rawConfig);
    }

    // (每一个address，每一个应用名一个url)override://address/{接口的全限定类名}?[group={group}]&[version={version}]&category=dynamicconfigurators&&side={ConfigItem中配置的side}&providerAddresses={ConfigItem中配置的providerAddresses，逗号分隔}...(ConfigItem中的参数)&enabled={enabled(如果item的type参数为空或者为general，则启用 外部ConfiguratorConfig的enable参数)}&category=dynamicconfigurators&&configVersion={配置中的ConfigVersion}&application={配置的应用名1}
    private static List<URL> serviceItemToUrls(ConfigItem item, ConfiguratorConfig config) {
        List<URL> urls = new ArrayList<>();
        //从item中获取 应用 该配置的 ip地址，如果为空，则表示应用到所有符合条件的ip上，使用 ANYHOST_VALUE("0.0.0.0")
        List<String> addresses = parseAddresses(item);

        addresses.forEach(addr -> {
            StringBuilder urlBuilder = new StringBuilder();
            // override://address/
            urlBuilder.append("override://").append(addr).append("/");

            // {接口的全限定类名}?[group={group}]&[version={version}]
            urlBuilder.append(appendService(config.getKey()));
            //从item中获取构建查询参数并封装： category=dynamicconfigurators&&side={ConfigItem中配置的side}&providerAddresses={ConfigItem中配置的providerAddresses，逗号分隔}...(ConfigItem中的参数)
            urlBuilder.append(toParameterString(item));

            //enabled={enabled(如果item的type参数为空或者为general，则启用 外部ConfiguratorConfig的enable参数)}
            parseEnabled(item, config, urlBuilder);

            //&category=dynamicconfigurators&&configVersion={配置中的ConfigVersion}
            urlBuilder.append("&category=").append(Constants.DYNAMIC_CONFIGURATORS_CATEGORY);
            urlBuilder.append("&configVersion=").append(config.getConfigVersion());

            List<String> apps = item.getApplications();
            if (apps != null && apps.size() > 0) {
                apps.forEach(app -> {
                    //&application={配置的应用名}...
                    urls.add(URL.valueOf(urlBuilder.append("&application=").append(app).toString()));
                });
            } else {
                urls.add(URL.valueOf(urlBuilder.toString()));
            }
        });

        return urls;
    }

    // 没有指定服务key: override://{应用配置的节点ip(每个ip一个url)}/*?category=dynamicconfigurators&&side={ConfigItem中配置的side}&providerAddresses={ConfigItem中配置的providerAddresses，逗号分隔}...(ConfigItem中的参数)&application={配置中的key}&enabled={enabled(如果item的type参数为空或者为general，则启用 外部ConfiguratorConfig的enable参数)}&category=appdynamicconfigurators&configVersion={ConfiguratorConfig的configversion参数}
    // 指定了 服务key: override://{应用配置的节点ip(每个ip一个url)}/{服务接口名(每个服务一个url，todo ？ 这段代码多服务是不是有bug，在服务循环的时候 没有把 上一次添加的接口名等信息去除)}?[group={group}]&[version={version}]category=dynamicconfigurators&&side={ConfigItem中配置的side}&providerAddresses={ConfigItem中配置的providerAddresses，逗号分隔}...(ConfigItem中的参数)&application={配置中的key}&enabled={enabled(如果item的type参数为空或者为general，则启用 外部ConfiguratorConfig的enable参数)}&category=appdynamicconfigurators&configVersion={ConfiguratorConfig的configversion参数}
    private static List<URL> appItemToUrls(ConfigItem item, ConfiguratorConfig config) {
        List<URL> urls = new ArrayList<>();
        //从item中获取 应用 该配置的 ip地址，如果为空，则表示应用到所有符合条件的ip上，使用 ANYHOST_VALUE("0.0.0.0")
        List<String> addresses = parseAddresses(item);
        for (String addr : addresses) {
            StringBuilder urlBuilder = new StringBuilder();
            // override://{应用本写配置的节点ip}/*（如果item没有配置services参数）
            // override://{应用本写配置的节点ip}/*（如果item配置services参数）
            urlBuilder.append("override://").append(addr).append("/");
            List<String> services = item.getServices();
            if (services == null) {
                services = new ArrayList<>();
            }
            if (services.size() == 0) {
                services.add("*");
            }
            for (String s : services) {
                //servicekey格式：[group/]{接口的全限定类名}:[version]
                // {接口的全限定类名}?[group={group}]&[version={version}]
                urlBuilder.append(appendService(s));
                //category=dynamicconfigurators&&side={ConfigItem中配置的side}&providerAddresses={ConfigItem中配置的providerAddresses，逗号分隔}...(ConfigItem中的参数)
                urlBuilder.append(toParameterString(item));
                //&application={配置中的key}
                urlBuilder.append("&application=").append(config.getKey());
                //&enabled={enabled(如果item的type参数为空或者为general，则启用 外部ConfiguratorConfig的enable参数)}
                parseEnabled(item, config, urlBuilder);

                //&category=appdynamicconfigurators
                urlBuilder.append("&category=").append(Constants.APP_DYNAMIC_CONFIGURATORS_CATEGORY);
                //&configVersion={ConfiguratorConfig的configversion参数}
                urlBuilder.append("&configVersion=").append(config.getConfigVersion());

                urls.add(URL.valueOf(urlBuilder.toString()));
            }
        }
        return urls;
    }

    //从item中获取构建查询参数并封装： category=dynamicconfigurators&&side={ConfigItem中配置的side}&providerAddresses={ConfigItem中配置的providerAddresses，逗号分隔}...(ConfigItem中的参数)
    private static String toParameterString(ConfigItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("category=");
        sb.append(Constants.DYNAMIC_CONFIGURATORS_CATEGORY);
        if (item.getSide() != null) {
            sb.append("&side=");
            sb.append(item.getSide());
        }
        Map<String, String> parameters = item.getParameters();
        if (CollectionUtils.isEmptyMap(parameters)) {
            throw new IllegalStateException("Invalid configurator rule, please specify at least one parameter " +
                    "you want to change in the rule.");
        }

        parameters.forEach((k, v) -> {
            sb.append("&");
            sb.append(k);
            sb.append("=");
            sb.append(v);
        });

        if (CollectionUtils.isNotEmpty(item.getProviderAddresses())) {
            sb.append("&");
            sb.append(Constants.OVERRIDE_PROVIDERS_KEY);
            sb.append("=");
            sb.append(CollectionUtils.join(item.getProviderAddresses(), ","));
        }

        return sb.toString();
    }



    //servicekey格式：[group/]{接口的全限定类名}:[version]
    // {接口的全限定类名}?[group={group}]&[version={version}]
    private static String appendService(String serviceKey) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isEmpty(serviceKey)) {
            throw new IllegalStateException("service field in configuration is null.");
        }

        String interfaceName = serviceKey;
        int i = interfaceName.indexOf("/");
        if (i > 0) {
            sb.append("group=");
            sb.append(interfaceName, 0, i);
            sb.append("&");

            interfaceName = interfaceName.substring(i + 1);
        }
        int j = interfaceName.indexOf(":");
        if (j > 0) {
            sb.append("version=");
            sb.append(interfaceName.substring(j + 1));
            sb.append("&");
            interfaceName = interfaceName.substring(0, j);
        }
        sb.insert(0, interfaceName + "?");

        return sb.toString();
    }

    public static void main(String[] args) {
        String servicekey = "52rp/demoService:520";
        System.out.println(appendService(servicekey));
    }

    // enabled={enabled(如果item的type参数为空或者为general，则启用 外部ConfiguratorConfig的enable参数)}
    private static void parseEnabled(ConfigItem item, ConfiguratorConfig config, StringBuilder urlBuilder) {
        urlBuilder.append("&enabled=");
        if (item.getType() == null || ConfigItem.GENERAL_TYPE.equals(item.getType())) {
            urlBuilder.append(config.getEnabled());
        } else {
            urlBuilder.append(item.getEnabled());
        }
    }

    /**
     * 从item中获取 应用 该配置的 ip地址，如果为空，则表示应用到所有符合条件的ip上，使用 ANYHOST_VALUE("0.0.0.0")
     * @param item
     * @return
     */
    private static List<String> parseAddresses(ConfigItem item) {
        List<String> addresses = item.getAddresses();
        if (addresses == null) {
            addresses = new ArrayList<>();
        }
        if (addresses.size() == 0) {
            addresses.add(Constants.ANYHOST_VALUE);
        }
        return addresses;
    }
}
