/**
 * ymm56.com Inc.
 * Copyright (c) 2013-2019 All Rights Reserved.
 */
package org.apache.dubbo.container;
//是一个 Standlone 的容器，以简单的 Main 加载 Spring 启动，因为服务通常不需要 Tomcat/JBoss 等 Web 容器的特性，没必要用 Web 容器去加载服务。

//因为后台服务不需要Tomcat/JBoss 等 Web 容器的功能，不需要用这些厚实的容器去加载服务提供方，既资源浪费，又增加复杂度。服务容器只是一个简单的Main方法，加载一些内置的容器，也支持扩展容器。