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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AbstractRegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * @see org.apache.dubbo.registry.RegistryFactory
 */
public abstract class AbstractRegistryFactory implements RegistryFactory {

    // 日志记录
    // Log output
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRegistryFactory.class);

    // 锁，对REGISTRIES访问对竞争控制
    // The lock for the acquisition process of the registry
    private static final ReentrantLock LOCK = new ReentrantLock();

    // Registry 集合
    // Registry Collection Map<{真实的注册中心协议}://注册中心ip/[group/]/org.apache.dubbo.registry.RegistryService:version, Registry>
    private static final Map<String, Registry> REGISTRIES = new HashMap<>();

    /**
     * Get all registries
     *
     * @return all registries
     */
    public static Collection<Registry> getRegistries() {
        return Collections.unmodifiableCollection(REGISTRIES.values());
    }

    /**
     * Close all created registries
     */
    // 销毁所有的Registry对象，并且清除内存缓存，逻辑比较简单，关键就是对REGISTRIES进行同步的操作。
    // TODO: 2017/8/30 to move somewhere else better
    public static void destroyAll() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Close all registries " + getRegistries());
        }
        // Lock up the registry shutdown process
        // 获得锁
        LOCK.lock();
        try {
            for (Registry registry : getRegistries()) {
                try {
                    // 销毁
                    registry.destroy();
                } catch (Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            // 清空缓存
            REGISTRIES.clear();
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    //先从缓存中取，如果没有命中，则创建注册中心实例
    @Override
    public Registry getRegistry(URL url) {
        // 修改url  {真实的注册中心协议}://注册中心ip+端口/org.apache.dubbo.registry.RegistryService?interface={org.apache.dubbo.registry.RegistryService}&proxy={服务url中的代理参数}和applicationConfig、RegistryConfig中获取的其他查询参数
        url = URLBuilder.from(url)
                .setPath(RegistryService.class.getName())
                .addParameter(Constants.INTERFACE_KEY, RegistryService.class.getName())
                .removeParameters(Constants.EXPORT_KEY, Constants.REFER_KEY)
                .build();
        // 计算key值  {真实的注册中心协议}://注册中心ip/[group/]/org.apache.dubbo.registry.RegistryService:version
        String key = url.toServiceStringWithoutResolving();
        // Lock the registry access process to ensure a single instance of the registry
        // 获得锁
        LOCK.lock();
        try {
            Registry registry = REGISTRIES.get(key);
            if (registry != null) {
                return registry;
            }
            // 创建Registry对象
            //create registry by spi/ioc
            registry = createRegistry(url);
            if (registry == null) {
                throw new IllegalStateException("Can not create registry " + url);
            }
            // 添加到缓存
            REGISTRIES.put(key, registry);
            return registry;
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    protected abstract Registry createRegistry(URL url);

}
