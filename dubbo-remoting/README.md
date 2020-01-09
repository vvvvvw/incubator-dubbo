相当于 Dubbo 协议的实现，如果 RPC 用 RMI协议则不需要使用此包。
抽象了exchange和transport这两层
提供了多种客户端和服务端通信功能，比如基于Grizzly、Netty、Tomcat等等，RPC用除了RMI的协议都要用到此模块。