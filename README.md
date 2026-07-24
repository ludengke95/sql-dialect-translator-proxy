# SDTP — SQL Dialect Translator Proxy

`sdtp` 是一个基于 Netty 实现的高性能透明 SQL 方言转换 TCP 代理服务器，支持 MySQL / PostgreSQL 协议，允许任意语言（Go、Python、Node.js、Java、C# 等）客户端零侵入连接并自动改写 SQL 方言。

## 模块结构

- **sdtp-protocol**: 代理协议基础层
- **sdtp-protocol-mysql**: MySQL 线协议编解码与认证处理
- **sdtp-protocol-pg**: PostgreSQL 线协议编解码
- **sdtp-core**: Netty Handler 核心管道与会话管理
- **sdtp-backend**: 整合 `sdt-core` 执行 SQL 改写与后端 JDBC 数据源交互
- **sdtp-server**: Proxy 主服务入口 (`ProxyBootstrap`)
- **sdtp-metrics**: 代理监控指标

## 快速开始

### 1. 构建
在构建前请确保本地仓库已 install `sdt-core`（即 `sdt` 项目）：
```bash
mvn clean package -DskipTests
```

### 2. 运行代理服务
```bash
java -jar sdtp-server/target/sdtp-server-1.0.0-SNAPSHOT-jar-with-dependencies.jar
```

或使用 Docker 启动：
```bash
docker compose -f docker/docker-compose.yml up
```
