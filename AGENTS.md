# SDTP — SQL Dialect Translator Proxy

透明 SQL 方言转换 TCP 代理：MySQL/PG 协议 TCP 代理（支持任意语言客户端连接）。

## 项目

- **技术栈**：Java 8、Maven 多模块、Apache Calcite（SQL 改写）、Netty 4.1（协议代理）、HikariCP（连接池）、SLF4J+Logback、JUnit 4 + Testcontainers、SnakeYAML。
- **入口点**：`com.translator.proxy.server.ProxyBootstrap` — Netty `main()`（`sdtp-server` 模块）
- **Docker**：`docker` 目录 `Dockerfile`，`docker-compose.yml` 用于本地启动 Proxy + 数据库。

## 命令

| 操作 | 命令 |
|------|------|
| 全量构建 | `mvn clean package -DskipTests` |
| 运行所有测试 | `mvn test` |
| 运行单个测试类 | `mvn test -Dtest=HandshakeHandlerTest` |
| 运行单个模块 | `mvn test -pl sdtp-backend -am` |
| Docker 构建 | `docker compose -f docker/docker-compose.yml build` |
| Docker 启动 | `docker compose -f docker/docker-compose.yml up` |

## 架构

模块分布：

| 模块 | 职责 |
|------|------|
| **sdtp-protocol** | 基础协议编解码及常量定义。 |
| **sdtp-protocol-mysql** | MySQL 线协议编解码：`MySQLPacketDecoder`/`Encoder`、常量、认证（`MySQLAuth`）。 |
| **sdtp-protocol-pg** | PostgreSQL 线协议编解码支持。 |
| **sdtp-core** | 会话、认证、命令分发：`HandshakeHandler` → `AuthHandler` → `CommandHandler`。系统变量/SET/USE 模拟（`MySQLSystemCatalogProvider`）。 |
| **sdtp-backend** | JDBC 执行 + 翻译集成：`TranslationQueryProcessor`（依赖外部 `sdt-core`）、`JdbcBackendQueryProcessor`（裸 JDBC）、`ResultSetEncoder`。 |
| **sdtp-server** | Netty 启动引导、YAML 配置（`ConfigLoader`、`ProxyConfig`）、分发包打包。 |
| **sdtp-metrics** | Proxy 监控指标收集。 |

数据流（Proxy）：客户端 TCP → Netty IO → MySQLPacketDecoder → HandshakeHandler → AuthHandler → CommandHandler → (─direct 标记？裸 JDBC : Calcite 翻译 engine) → ResultSetEncoder → MySQLPacketEncoder → 客户端。

## 约定

- **包结构**：`com.translator.proxy.*`；代理子包与模块名一致（`com.translator.proxy.protocol`、`.core`、`.backend`、`.server`）。
- **日志**：`private static final Logger log = LoggerFactory.getLogger(ClassName.class);`，使用 SLF4J。
- **配置**：通过 SnakeYAML 加载 YAML（`ProxyConfig`/`ConfigLoader`）。
