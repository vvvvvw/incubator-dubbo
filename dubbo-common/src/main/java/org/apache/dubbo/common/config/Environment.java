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
package org.apache.dubbo.common.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TODO load as SPI will be better? 所有环境配置的组装
 */
public class Environment {
    private static final Environment INSTANCE = new Environment();

    //系统变量+配置文件 Map<{prefix}{id},如果后缀不是.，则添加上 . ；如果prefix和id 都为空，则使用默认值 dubbo，PropertiesConfiguration>
    private Map<String, PropertiesConfiguration> propertiesConfigs = new ConcurrentHashMap<>();
    //系统变量配置 Map<{prefix}{id},如果后缀不是.，则添加上 . ；如果prefix和id 都为空，则使用默认值 dubbo，SystemConfiguration>
    private Map<String, SystemConfiguration> systemConfigs = new ConcurrentHashMap<>();
    //环境变量配置 Map<{prefix}{id},如果后缀不是.，则添加上 . ；如果prefix和id 都为空，则使用默认值 dubbo，EnvironmentConfiguration>
    private Map<String, EnvironmentConfiguration> environmentConfigs = new ConcurrentHashMap<>();
    //// 内存配置，其实就是一个LinkedHashMap Map<{prefix}{id},如果后缀不是.，则添加上 . ；如果prefix和id 都为空，则使用默认值 dubbo，InmemoryConfiguration>
    private Map<String, InmemoryConfiguration> externalConfigs = new ConcurrentHashMap<>();
    //// 内存配置，其实就是一个LinkedHashMap Map<{prefix}{id},如果后缀不是.，则添加上 . ；如果prefix和id 都为空，则使用默认值 dubbo，InmemoryConfiguration>
    private Map<String, InmemoryConfiguration> appExternalConfigs = new ConcurrentHashMap<>();

    //全局级别的外部配置(如果ConfigCenterBean的includeSpringEnv属性为true，也可以获取spring中的属性)
    private Map<String, String> externalConfigurationMap = new HashMap<>(); //从外部配置中获取值(包括spring environment和外部配置)
    //应用级别的外部配置(如果ConfigCenterBean的includeSpringEnv属性为true，也可以获取spring中的属性)
    private Map<String, String> appExternalConfigurationMap = new HashMap<>();//从外部配置中获取值(包括spring environment和外部配置)

    private boolean configCenterFirst = true;

    /**
     * FIXME, this instance will always be a type of DynamicConfiguration, ConfigCenterConfig will load the instance at startup and assign it to here.
     */
    private Configuration dynamicConfiguration;

    public static Environment getInstance() {
        return INSTANCE;
    }

    public PropertiesConfiguration getPropertiesConfig(String prefix, String id) {
        return propertiesConfigs.computeIfAbsent(toKey(prefix, id), k -> new PropertiesConfiguration(prefix, id));
    }

    public SystemConfiguration getSystemConfig(String prefix, String id) {
        return systemConfigs.computeIfAbsent(toKey(prefix, id), k -> new SystemConfiguration(prefix, id));
    }

    public InmemoryConfiguration getExternalConfig(String prefix, String id) {
        return externalConfigs.computeIfAbsent(toKey(prefix, id), k -> {
            InmemoryConfiguration configuration = new InmemoryConfiguration(prefix, id);
            configuration.setProperties(externalConfigurationMap);
            return configuration;
        });
    }

    public InmemoryConfiguration getAppExternalConfig(String prefix, String id) {
        return appExternalConfigs.computeIfAbsent(toKey(prefix, id), k -> {
            InmemoryConfiguration configuration = new InmemoryConfiguration(prefix, id);
            configuration.setProperties(appExternalConfigurationMap);
            return configuration;
        });
    }

    public EnvironmentConfiguration getEnvironmentConfig(String prefix, String id) {
        return environmentConfigs.computeIfAbsent(toKey(prefix, id), k -> new EnvironmentConfiguration(prefix, id));
    }

    public void setExternalConfigMap(Map<String, String> externalConfiguration) {
        this.externalConfigurationMap = externalConfiguration;
    }

    public void setAppExternalConfigMap(Map<String, String> appExternalConfiguration) {
        this.appExternalConfigurationMap = appExternalConfiguration;
    }

    public Map<String, String> getExternalConfigurationMap() {
        return externalConfigurationMap;
    }

    public Map<String, String> getAppExternalConfigurationMap() {
        return appExternalConfigurationMap;
    }

    public void updateExternalConfigurationMap(Map<String, String> externalMap) {
        this.externalConfigurationMap.putAll(externalMap);
    }

    public void updateAppExternalConfigurationMap(Map<String, String> externalMap) {
        this.appExternalConfigurationMap.putAll(externalMap);
    }

    /**
     * Create new instance for each call, since it will be called only at startup, I think there's no big deal of the potential cost.
     * Otherwise, if use cache, we should make sure each Config has a unique id which is difficult to guarantee because is on the user's side,
     * especially when it comes to ServiceConfig and ReferenceConfig.
     * 将所有环境配置 按照优先级加载
     * @param prefix
     * @param id
     * @return
     */
    // 将所有环境配置 按照优先级加载 系统配置>应用级别的外部配置>全局级别的外部配置>配置文件
    public CompositeConfiguration getConfiguration(String prefix, String id) {
        CompositeConfiguration compositeConfiguration = new CompositeConfiguration();
        // Config center has the highest priority
        //系统配置
        compositeConfiguration.addConfiguration(this.getSystemConfig(prefix, id));
        //应用级别的外部配置
        compositeConfiguration.addConfiguration(this.getAppExternalConfig(prefix, id));
        //全局级别的外部配置
        compositeConfiguration.addConfiguration(this.getExternalConfig(prefix, id));
        //配置文件
        compositeConfiguration.addConfiguration(this.getPropertiesConfig(prefix, id));
        return compositeConfiguration;
    }

    public Configuration getConfiguration() {
        return getConfiguration(null, null);
    }

    // 返回{prefix}{id},如果后缀不是.，则添加上 . ；如果prefix和id 都为空，则返回 dubbo
    private static String toKey(String prefix, String id) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotEmpty(prefix)) {
            sb.append(prefix);
        }
        if (StringUtils.isNotEmpty(id)) {
            sb.append(id);
        }

        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '.') {
            sb.append(".");
        }

        if (sb.length() > 0) {
            return sb.toString();
        }
        return Constants.DUBBO;
    }

    public boolean isConfigCenterFirst() {
        return configCenterFirst;
    }

    public void setConfigCenterFirst(boolean configCenterFirst) {
        this.configCenterFirst = configCenterFirst;
    }

    public Optional<Configuration> getDynamicConfiguration() {
        return Optional.ofNullable(dynamicConfiguration);
    }

    public void setDynamicConfiguration(Configuration dynamicConfiguration) {
        this.dynamicConfiguration = dynamicConfiguration;
    }

    // For test
    public void clearExternalConfigs() {
        this.externalConfigs.clear();
        this.externalConfigurationMap.clear();
    }

    // For test
    public void clearAppExternalConfigs() {
        this.appExternalConfigs.clear();
        this.appExternalConfigurationMap.clear();
    }
}
