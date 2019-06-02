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
package org.apache.dubbo.rpc.cluster.configurator;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AbstractOverrideConfigurator
 */
//该类实现了Configurator接口，是配置规则 抽象类，配置有两种方式，一种是没有时添加配置，这种暂时没有用到，另一种是覆盖配置。
public abstract class AbstractConfigurator implements Configurator {

    //在配置中心动态配置的规则
    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    /**
     * 需要经过 configuratorUrl 覆写的url
     * @param url - old provider url.
     * @return
     */
    @Override
    public URL configure(URL url) {
        // TODO: host为空不是表示是 服务或者应用对应的所有host么？ by 15258 2019/6/1 12:19
        //如果 配置的规则 没有启用或者 url中的host为空
        // If override url is not enabled or is invalid, just return.
        if (!configuratorUrl.getParameter(Constants.ENABLED_KEY, true) || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }
        /**
         * This if branch is created since 2.7.0.
         */
        // 获取配置版本号
        String apiVersion = configuratorUrl.getParameter(Constants.CONFIG_VERSION_KEY);
        if (StringUtils.isNotEmpty(apiVersion)) {
            /** 当前属于 客户端还是服务端 **/
            String currentSide = url.getParameter(Constants.SIDE_KEY);
            /** 规则属于 客户端还是服务端 **/
            String configuratorSide = configuratorUrl.getParameter(Constants.SIDE_KEY);
            if (currentSide.equals(configuratorSide) && Constants.CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) {
                // 为了控制消费侧，并且没有配置端口
                url = configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (currentSide.equals(configuratorSide) && Constants.PROVIDER.equals(configuratorSide) && url.getPort() == configuratorUrl.getPort()) {
                // 为了控制服务侧，并且配置的端口和服务端口一致
                url = configureIfMatch(url.getHost(), url);
            }
        }
        /**
         * This else branch is deprecated and is left only to keep compatibility with versions before 2.7.0
         */
        else {
            url = configureDeprecated(url);
        }
        return url;
    }

    //老版本的会在 服务端和客户端都生效，根据端口来判断是使用 需要调用的服务的ip还是 本机ip 来匹配
    @Deprecated
    private URL configureDeprecated(URL url) {
        // 如果覆盖url具有端口，则表示它是提供者地址。我们希望使用此覆盖URL控制特定提供程序，它可以在提供端生效 也可以在消费端生效。
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else {
            // 配置规则，URL 没有端口，意味着override 输入消费端地址 或者 0.0.0.0
            // override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0
            // 1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore;
            // 2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider
            if (url.getParameter(Constants.SIDE_KEY, Constants.PROVIDER).equals(Constants.CONSUMER)) {
                // 如果它是一个消费者ip地址，目的是控制一个特定的消费者实例，它必须在消费者一方生效，任何提供者收到这个覆盖url应该忽略;
                return configureIfMatch(NetUtils.getLocalHost(), url);// NetUtils.getLocalHost is the ip address consumer registered to registry.

            } else if (url.getParameter(Constants.SIDE_KEY, Constants.CONSUMER).equals(Constants.PROVIDER)) {
                // 如果ip为0.0.0.0，则此覆盖url可以在使用者上使用，也可以在提供者上使用
                return configureIfMatch(Constants.ANYHOST_VALUE, url);// take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
            }
        }
        return url;
    }

    /**
     *
     * @param host
     * @param url
     * @return
     */
    //当条件匹配时，才对url进行配置。
    private URL configureIfMatch(String host, URL url) {
        // 匹配 Host
        if (Constants.ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // TODO, to support wildcards
            String providers = configuratorUrl.getParameter(Constants.OVERRIDE_PROVIDERS_KEY);
            if (StringUtils.isEmpty(providers) || providers.contains(url.getAddress()) || providers.contains(Constants.ANYHOST_VALUE)) {
                //匹配providerAddresses
                String configApplication = configuratorUrl.getParameter(Constants.APPLICATION_KEY,
                        configuratorUrl.getUsername());
                String currentApplication = url.getParameter(Constants.APPLICATION_KEY, url.getUsername());
                if (configApplication == null || Constants.ANY_VALUE.equals(configApplication)
                        || configApplication.equals(currentApplication)) {
                    //匹配application
                    // 配置 URL 中的条件 KEYS 集合。其中下面四个 KEY ，不算是条件，而是内置属性。考虑到下面要移除，所以添加到该集合中。
                    Set<String> conditionKeys = new HashSet<String>();
                    conditionKeys.add(Constants.CATEGORY_KEY);
                    conditionKeys.add(Constants.CHECK_KEY);
                    conditionKeys.add(Constants.DYNAMIC_KEY);
                    conditionKeys.add(Constants.ENABLED_KEY);
                    conditionKeys.add(Constants.GROUP_KEY);
                    conditionKeys.add(Constants.VERSION_KEY);
                    conditionKeys.add(Constants.APPLICATION_KEY);
                    conditionKeys.add(Constants.SIDE_KEY);
                    conditionKeys.add(Constants.CONFIG_VERSION_KEY);
                    // 判断传入的 url 是否匹配配置规则 URL 的条件。
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        // 除了 "application" 和 "side" 之外，带有 `"~"` 开头的 KEY ，也是条件。
                        if (key.startsWith("~") || Constants.APPLICATION_KEY.equals(key) || Constants.SIDE_KEY.equals(key)) {
                            // 添加搭配条件集合
                            conditionKeys.add(key);
                            if (value != null && !Constants.ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                                return url;
                            }
                        }
                    }
                    // 移除条件 KEYS 集合，并将剩余的属性配置到 URL 中
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
