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
package org.apache.dubbo.config.spring;

import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.spring.extension.SpringExtensionFactory;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Since 2.7.0+, export and refer will only be executed when Spring is fully initialized, and each Config bean will get refreshed on the start of the export and refer process.
 * So it's ok for this bean not to be the first Dubbo Config bean being initialized.
 * <p>
 * If use ConfigCenterConfig directly, you should make sure ConfigCenterConfig.init() is called before actually export/refer any Dubbo service.
 */
public class ConfigCenterBean extends ConfigCenterConfig implements InitializingBean, ApplicationContextAware, DisposableBean, EnvironmentAware {

    private transient ApplicationContext applicationContext;

    private Boolean includeSpringEnv = false;
    private ApplicationConfig application;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        SpringExtensionFactory.addApplicationContext(applicationContext);
    }

   //如果application属性没有设置，则获取spring容器中 default为null或者true的 applicationConfig实例并设置到 application属性上，为之后的 应用特定的外部化配置 做准备
    @Override
    public void afterPropertiesSet() throws Exception {
        if (getApplication() == null) {
            // 如果没有指定 application 参数，则 从 spring容器中 获取所有 的 ApplicationConfig实例
            Map<String, ApplicationConfig> applicationConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ApplicationConfig.class, false, false);
            if (applicationConfigMap != null && applicationConfigMap.size() > 0) {
                ApplicationConfig applicationConfig = null;
                for (ApplicationConfig config : applicationConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (applicationConfig != null) {
                            throw new IllegalStateException("Duplicate application configs: " + applicationConfig + " and " + config);
                        }
                        applicationConfig = config;
                    }
                }
                if (applicationConfig != null) {
                    setApplication(applicationConfig);
                }
            }
        }
    }

    @Override
    public void destroy() throws Exception {

    }
    //如果 includeSpringEnv属性为true，则从 environment获取 全部和应用特定 外部配置 并设置到 内存配置中
    @Override
    public void setEnvironment(Environment environment) {
        if (includeSpringEnv) {
            // 从 environment中获取 key为 dubbo.properties(默认，可以自定义) 的value，
            //1.如果value类型为map，则将map中所有元素都返回；2.如果value类型为string，则将string解析为键值对后返回；
            Map<String, String> externalProperties = getConfigurations(getConfigFile(), environment);
            // 从 environment中获取 key对应 的value，key：
            // appConfigFile字段值 > {appName字段值}.dubbo.properties（后缀dubbo.properties可以自定义） > application..dubbo.properties（后缀dubbo.properties可以自定义）
            //1.如果value类型为map，则将map中所有元素都返回；2.如果value类型为string，则将string解析为键值对后返回；
            Map<String, String> appExternalProperties = getConfigurations(StringUtils.isNotEmpty(getAppConfigFile()) ? getAppConfigFile() : (StringUtils.isEmpty(getAppName()) ? ("application." + getConfigFile()) : (getAppName() + "." + getConfigFile())), environment);
            org.apache.dubbo.common.config.Environment.getInstance().setExternalConfigMap(externalProperties);
            org.apache.dubbo.common.config.Environment.getInstance().setAppExternalConfigMap(appExternalProperties);
        }
    }

    // 从 environment中 获取 key对应的 value。1.如果value类型为map，则将map中所有元素都返回；2.如果value类型为string，则将string解析为键值对后返回；
    private Map<String, String> getConfigurations(String key, Environment environment) {
        Object rawProperties = environment.getProperty(key, Object.class);
        Map<String, String> externalProperties = new HashMap<>();
        try {
            if (rawProperties instanceof Map) {
                externalProperties.putAll((Map<String, String>) rawProperties);
            } else if (rawProperties instanceof String) {
                externalProperties.putAll(ConfigurationUtils.parseProperties((String) rawProperties));
            }

            if (environment instanceof ConfigurableEnvironment && externalProperties.isEmpty()) {
                ConfigurableEnvironment configurableEnvironment = (ConfigurableEnvironment) environment;
                PropertySource propertySource = configurableEnvironment.getPropertySources().get(key);
                if (propertySource != null) {
                    Object source = propertySource.getSource();
                    if (source instanceof Map) {
                        ((Map<String, Object>) source).forEach((k, v) -> {
                            externalProperties.put(k, (String) v);
                        });
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return externalProperties;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    public Boolean getIncludeSpringEnv() {
        return includeSpringEnv;
    }

    public void setIncludeSpringEnv(Boolean includeSpringEnv) {
        this.includeSpringEnv = includeSpringEnv;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    public void setApplication(ApplicationConfig application) {
        this.application = application;
    }
}
