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
package org.apache.dubbo.registry.dubbo;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.rpc.Invoker;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DubboRegistry
 */ //默认远程提供服务
public class DubboRegistry extends FailbackRegistry {

    // 日志记录
    private final static Logger logger = LoggerFactory.getLogger(DubboRegistry.class);

    // 重新连接周期：3秒
    // Reconnecting detection cycle: 3 seconds (unit:millisecond)
    private static final int RECONNECT_PERIOD_DEFAULT = 3 * 1000;

    // 任务调度器
    // Scheduled executor service
    private final ScheduledExecutorService reconnectTimer = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryReconnectTimer", true));

    //这里的重连是建立了一个计时器，并且会定期检查连接是否可用，如果不可用，就无限重连
    // 重新连接执行器，定期检查连接可用，如果不可用，则无限制重连
    // Reconnection timer, regular check connection is available. If unavailable, unlimited reconnection.
    private final ScheduledFuture<?> reconnectFuture;

    // 客户端的锁，保证客户端的原子性，可见行，线程安全
    // The lock for client acquisition process, lock the creation process of the client instance to prevent repeated clients
    private final ReentrantLock clientLock = new ReentrantLock();

    // 注册中心Invoker
    private final Invoker<RegistryService> registryInvoker;

    // 注册中心服务对象
    private final RegistryService registryService;

    // 任务调度器reconnectTimer将等待的时间
    /**
     * The time in milliseconds the reconnectTimer will wait
     */
    private final int reconnectPeriod;

    public DubboRegistry(Invoker<RegistryService> registryInvoker, RegistryService registryService) {
        // 调用父类FailbackRegistry的构造函数
        super(registryInvoker.getUrl());
        this.registryInvoker = registryInvoker;
        this.registryService = registryService;
        // Start reconnection timer
        // 优先取url中key为reconnect.perio的配置，如果没有，则使用默认的3s
        this.reconnectPeriod = registryInvoker.getUrl().getParameter(Constants.REGISTRY_RECONNECT_PERIOD_KEY, RECONNECT_PERIOD_DEFAULT);
        // 每reconnectPeriod秒去连接，首次连接也延迟reconnectPeriod秒
        // 创建了一个重连计时器，一定的间隔时间去检查是否断开，如果断开就进行连接。
        reconnectFuture = reconnectTimer.scheduleWithFixedDelay(() -> {
            // Check and connect to the registry
            try {
                connect();
            } catch (Throwable t) { // Defensive fault tolerance
                logger.error("Unexpected error occur at reconnect, cause: " + t.getMessage(), t);
            }
        }, reconnectPeriod, reconnectPeriod, TimeUnit.MILLISECONDS);
    }

    protected final void connect() {
        try {
            // 检查注册中心是否已连接
            // Check whether or not it is connected
            if (isAvailable()) {
                return;
            }
            if (logger.isInfoEnabled()) {
                logger.info("Reconnect to registry " + getUrl());
            }
            // 获得客户端锁
            clientLock.lock();
            try {
                // Double check whether or not it is connected
                // 二次查询注册中心是否已经连接
                if (isAvailable()) {
                    return;
                }
                // 恢复注册和订阅
                recover();
            } finally {
                // 释放锁
                clientLock.unlock();
            }
        } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
            if (getUrl().getParameter(Constants.CHECK_KEY, true)) {
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                throw new RuntimeException(t.getMessage(), t);
            }
            logger.error("Failed to connect to registry " + getUrl().getAddress() + " from provider/consumer " + NetUtils.getLocalHost() + " use dubbo " + Version.getVersion() + ", cause: " + t.getMessage(), t);
        }
    }

    //用来检查注册中心是否连接
    @Override
    public boolean isAvailable() {
        if (registryInvoker == null) {
            return false;
        }
        return registryInvoker.isAvailable();
    }

    // 取消重新连接计时器
    @Override
    public void destroy() {
        super.destroy();
        try {
            // Cancel the reconnection timer
            ExecutorUtil.cancelScheduledFuture(reconnectFuture);
        } catch (Throwable t) {
            logger.warn("Failed to cancel reconnect timer", t);
        }
        // 销毁注册中心的Invoker
        registryInvoker.destroy();
        // 关闭任务调度器
        ExecutorUtil.gracefulShutdown(reconnectTimer, reconnectPeriod);
    }

    @Override
    public void doRegister(URL url) {
        registryService.register(url);
    }

    @Override
    public void doUnregister(URL url) {
        registryService.unregister(url);
    }

    @Override
    public void doSubscribe(URL url, NotifyListener listener) {
        registryService.subscribe(url, listener);
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        registryService.unsubscribe(url, listener);
    }

    @Override
    public List<URL> lookup(URL url) {
        return registryService.lookup(url);
    }

}
