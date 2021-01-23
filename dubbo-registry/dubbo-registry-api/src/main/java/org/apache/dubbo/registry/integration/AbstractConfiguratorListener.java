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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.configcenter.ConfigChangeEvent;
import org.apache.dubbo.configcenter.ConfigChangeType;
import org.apache.dubbo.configcenter.ConfigurationListener;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.configurator.parser.ConfigParser;

import java.util.Collections;
import java.util.List;

/**
 * AbstractConfiguratorListener
 */
public abstract class AbstractConfiguratorListener implements ConfigurationListener {
    private static final Logger logger = LoggerFactory.getLogger(AbstractConfiguratorListener.class);

    //服务治理 外部配置url 解析出来规则，其中有 在配置中心动态配置的规则
    protected List<Configurator> configurators = Collections.emptyList();


    protected final void initWith(String key) {
        // 返回动态配置 动态配置中心
        DynamicConfiguration dynamicConfiguration = DynamicConfiguration.getDynamicConfiguration();
        // 添加外部配置监听器，通过key进行区分
        dynamicConfiguration.addListener(key, this);
        // 获取初始值
        String rawConfig = dynamicConfiguration.getConfig(key);
        if (!StringUtils.isEmpty(rawConfig)) {
            process(new ConfigChangeEvent(key, rawConfig));
        }
    }

    @Override
    public void process(ConfigChangeEvent event) {
        if (logger.isInfoEnabled()) {
            logger.info("Notification of overriding rule, change type is: " + event.getChangeType() +
                    ", raw config content is:\n " + event.getValue());
        }

        if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
            //如果是删除事件，则清除对应的configurators对象
            configurators.clear();
        } else {
            try {
                // 负责解析配置生成configurators对象
                // parseConfigurators will recognize app/service config automatically.
                configurators = Configurator.toConfigurators(ConfigParser.parseConfigurators(event.getValue()))
                        .orElse(configurators);
            } catch (Exception e) {
                logger.error("Failed to parse raw dynamic config and it will not take effect, the raw config is: " +
                        event.getValue(), e);
                return;
            }
        }

        notifyOverrides();
    }

    protected abstract void notifyOverrides();

    public List<Configurator> getConfigurators() {
        return configurators;
    }

    public void setConfigurators(List<Configurator> configurators) {
        this.configurators = configurators;
    }
}
