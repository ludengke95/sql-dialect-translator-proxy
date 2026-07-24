# SDT Prometheus 监控指标

> SDT 内置了 Prometheus 指标采集，覆盖连接、命令、翻译、后端执行、连接池、Netty 传输、JVM 七个层面。指标统一注册在 JVM 全局的 `io.prometheus.client.CollectorRegistry.defaultRegistry` 中。

---

## 目录

1. [SDT Proxy 模式](#1-sdt-proxy-模式)
2. [SDT JDBC 驱动模式](#2-sdt-jdbc-驱动模式)
   - [2.1 Spring Boot + Actuator（零配置）](#21-spring-boot--actuator零配置)
   - [2.2 非 Spring Boot 应用](#22-非-spring-boot-应用)
   - [2.3 自定义 Prometheus 端点](#23-自定义-prometheus-端点)
3. [完整指标清单](#3-完整指标清单)
4. [Prometheus 刮取配置](#4-prometheus-刮取配置)
5. [Grafana 常用 PromQL](#5-grafana-常用-promql)
6. [验证指标](#6-验证指标)

---

## 1. SDT Proxy 模式

### 配置

在 `proxy-config.yml` 中：

```yaml
proxy:
  port: 7788       # Proxy 监听端口

metrics:
  enabled: true    # 是否启用（默认 true）
  port: 0          # 指标端口，0 或留空 = proxy.port + 10000；显式指定则使用指定值
```

### 端口规则

| 配置情况 | 实际端口 |
|---------|---------|
| 不写 `metrics` 段 | `proxy.port + 10000` |
| 写了 `metrics` 但不写 `port` | `proxy.port + 10000` |
| `port: 0` | `proxy.port + 10000` |
| `port: 17788` | `17788` |

默认情况下 proxy 监听 `7788`，metrics 就是 `17788`。

### 验证

```bash
curl http://localhost:17788/metrics
```

---

## 2. SDT JDBC 驱动模式

SDT JDBC 驱动**不启动独立的 HTTP 服务**。所有指标已注册在 JVM 全局的 `CollectorRegistry.defaultRegistry`，你的应用只需暴露这个 registry 即可。

### 2.1 Spring Boot + Actuator（零配置）

如果你的应用是 Spring Boot 2.x/3.x，已自动适配，无需任何额外代码。

**Maven 依赖**（如果尚未引入）：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**application.yml**：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
```

访问 `http://localhost:8080/actuator/prometheus`，SDT 所有指标自动出现。

> **原理**：Micrometer 的 `PrometheusMeterRegistry` 在初始化时会扫描 `CollectorRegistry.defaultRegistry` 中已有的 Collector，将其合并到自己的输出中。SDT 驱动在类加载时就已完成 Collector 注册，所以 Spring Boot Actuator 的 `/actuator/prometheus` 端点会自动带上 SDT 指标。

### 2.2 非 Spring Boot 应用

在你的 `main()` 或初始化代码中添加一行：

```java
import io.prometheus.client.exporter.HTTPServer;

public class MyApp {
    public static void main(String[] args) throws Exception {
        // 启动 Prometheus HTTP 端点，SDT 指标自动出现在 /metrics
        new HTTPServer(9090);

        // ... 你的业务代码
    }
}
```

然后访问 `http://localhost:9090/metrics`。

### 2.3 自定义 Prometheus 端点

如果你的应用已有自定义的指标暴露端点，确保它从 `CollectorRegistry.defaultRegistry` 读取：

```java
// 使用 defaultRegistry 时 SDT 指标自动包含
new HTTPServer(9090);
// 或
new io.prometheus.client.exporter.MetricsServlet(CollectorRegistry.defaultRegistry);
```

> ⚠️ 不要 `new HTTPServer(port, CollectorRegistry.defaultRegistry, false)` 并传入一个**空的**自定义 Registry，否则 SDT 指标不会出现在端点上。

---

## 3. 完整指标清单

### 连接层

| 指标名 | 类型 | Label | 说明 |
|--------|------|-------|------|
| `sdt_connections_active` | Gauge | — | 当前活跃连接数 |
| `sdt_connections_total` | Counter | — | 启动以来连接总数 |
| `sdt_auth_success_total` | Counter | `auth_plugin` (native / sha256) | 认证成功次数 |
| `sdt_auth_failure_total` | Counter | `reason` (wrong_user / wrong_password / unsupported_plugin) | 认证失败次数 |

### 命令层

| 指标名 | 类型 | Label | 说明 |
|--------|------|-------|------|
| `sdt_commands_total` | Counter | `command` (COM_QUERY / COM_PING / COM_QUIT / COM_INIT_DB / ...) | 各命令计数 |
| `sdt_commands_duration_seconds` | Histogram | `command` | 命令处理耗时 |
| `sdt_commands_errors_total` | Counter | — | 命令处理异常总数 |
| `sdt_system_var_interceptions_total` | Counter | — | 系统变量拦截次数（SET / SELECT @@） |

### SQL 翻译层

| 指标名 | 类型 | Label | 说明 |
|--------|------|-------|------|
| `sdt_translation_requests_total` | Counter | `target_dialect` (postgresql / oracle / ...) | 翻译请求数 |
| `sdt_translation_success_total` | Counter | — | 翻译成功次数 |
| `sdt_translation_failures_total` | Counter | `error_type` (parse_error / translation_error) | 翻译失败次数 |
| `sdt_translation_fallbacks_total` | Counter | — | 翻译失败后降级为原始 SQL 的次数 |
| `sdt_translation_direct_total` | Counter | — | `-- direct` 直通跳过翻译的次数 |
| `sdt_translation_disabled_total` | Counter | — | 同方言无需翻译的次数 |
| `sdt_translation_duration_seconds` | Histogram | `target_dialect` | 翻译耗时（parse + rewrite + unparse） |

### 后端执行层

| 指标名 | 类型 | Label | 说明 |
|--------|------|-------|------|
| `sdt_backend_queries_total` | Counter | `backend_name`, `query_type` (SELECT / DML / DDL / OTHER) | 后端 JDBC 执行次数 |
| `sdt_backend_queries_duration_seconds` | Histogram | `backend_name` | JDBC 执行耗时 |
| `sdt_backend_errors_total` | Counter | `backend_name`, `sql_state` | SQL 执行错误次数 |
| `sdt_backend_result_rows` | Histogram | `backend_name` | 查询返回行数分布 |
| `sdt_backend_affected_rows` | Histogram | `backend_name` | DML 影响行数分布 |

### 连接池 (HikariCP)

| 指标名 | 类型 | Label | 说明 |
|--------|------|-------|------|
| `sdt_pool_active_connections` | Gauge | `pool_name` | 活跃连接数 |
| `sdt_pool_idle_connections` | Gauge | `pool_name` | 空闲连接数 |
| `sdt_pool_pending_connections` | Gauge | `pool_name` | 等待获取连接的线程数 |
| `sdt_pool_total_connections` | Gauge | `pool_name` | 连接池总连接数 |
| `sdt_pool_connection_timeout_total` | Counter | `pool_name` | 获取连接超时次数 |
| `sdt_pool_connection_creation_duration_seconds` | Histogram | `pool_name` | 物理连接创建耗时 |
| `sdt_pool_connection_acquire_duration_seconds` | Histogram | `pool_name` | 连接获取耗时 |
| `sdt_pool_connection_usage_duration_seconds` | Histogram | `pool_name` | 连接使用时长 |

### Netty 传输层

| 指标名 | 类型 | Label | 说明 |
|--------|------|-------|------|
| `sdt_netty_bytes_read_total` | Counter | — | Netty 读取字节总数 |
| `sdt_netty_bytes_written_total` | Counter | — | Netty 写出字节总数 |
| `sdt_netty_channels_active` | Gauge | — | 当前活跃 Channel 数 |

### 配置热更新 (Config Reload)

| 指标名 | 类型 | Label | 说明 |
|--------|------|-------|------|
| `sdt_config_reload_total` | Counter | `action` (add / remove / reload), `backend_name` | 后端变更次数 |
| `sdt_config_reload_failures_total` | Counter | — | 配置文件解析失败次数 |
| `sdt_config_reload_drain_total` | Counter | `backend_name`, `result` (ok / timeout) | reload drain 成功/超时次数 |
| `sdt_config_reload_queue_rejections_total` | Counter | `backend_name`, `reason` (full / timeout) | reload 期间请求被拒绝次数 |
| `sdt_config_reload_duration_seconds` | Histogram | `backend_name`, `action` | 单次 reload 耗时 |
| `sdt_backend_count` | Gauge | — | 当前后端总数 |

### JVM（自动注入）

通过 `simpleclient_hotspot` 自动注册，包括：

| 类别 | 典型指标 |
|------|---------|
| 内存 | `jvm_memory_used_bytes`, `jvm_memory_max_bytes` |
| GC | `jvm_gc_collection_seconds_count`, `jvm_gc_collection_seconds_sum` |
| 线程 | `jvm_threads_current`, `jvm_threads_daemon` |
| 类加载 | `jvm_classes_loaded` |

---

## 4. Prometheus 刮取配置

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'sdt-proxy'
    scrape_interval: 15s
    static_configs:
      - targets: ['proxy-host:17788']   # Proxy 模式：proxy 端口 + 10000

  - job_name: 'sdt-jdbc-app'
    scrape_interval: 15s
    metrics_path: '/actuator/prometheus'  # Spring Boot Actuator
    static_configs:
      - targets: ['app-host:8080']
```

---

## 5. Grafana 常用 PromQL

| 看板 | PromQL |
|------|--------|
| **每秒查询数 (QPS)** | `rate(sdt_commands_total{command="COM_QUERY"}[1m])` |
| **翻译成功率** | `sdt_translation_success_total / (sdt_translation_success_total + sdt_translation_failures_total)` |
| **翻译失败降级率** | `rate(sdt_translation_fallbacks_total[5m])` |
| **P50 翻译耗时** | `histogram_quantile(0.50, rate(sdt_translation_duration_seconds_bucket[5m]))` |
| **P99 翻译耗时** | `histogram_quantile(0.99, rate(sdt_translation_duration_seconds_bucket[5m]))` |
| **P99 后端执行耗时** | `histogram_quantile(0.99, rate(sdt_backend_queries_duration_seconds_bucket[5m]))` |
| **连接池使用率** | `sdt_pool_active_connections / sdt_pool_total_connections` |
| **连接池等待速率** | `rate(sdt_pool_connection_timeout_total[5m])` |
| **认证失败速率** | `rate(sdt_auth_failure_total[5m])` |
| **活跃连接数** | `sdt_connections_active` |
| **Netty 吞吐 (Bps)** | `rate(sdt_netty_bytes_read_total[1m]) + rate(sdt_netty_bytes_written_total[1m])` |
| **后端错误率** | `rate(sdt_backend_errors_total[5m])` |
| **直通比例** | `sdt_translation_direct_total / (sdt_translation_direct_total + sdt_translation_requests_total)` |
| **JVM 堆使用率** | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}` |
| **后端变更速率** | `rate(sdt_config_reload_total[5m])` |
| **Reload drain 超时率** | `rate(sdt_config_reload_drain_total{result="timeout"}[5m])` |
| **Reload 拒绝率** | `rate(sdt_config_reload_queue_rejections_total[5m])` |
| **后端数变化告警** | `delta(sdt_backend_count[5m]) < 0`（数值下降说明后端被移除） |

---

## 6. 验证指标

启动 SDT（Proxy 或 JDBC 应用）后，用 curl 验证：

**Proxy 模式**（假设 proxy 端口 7788）：
```bash
curl http://localhost:17788/metrics | grep "sdt_"
```

**Spring Boot 模式**：
```bash
curl http://localhost:8080/actuator/prometheus | grep "sdt_"
```

**自定义 HTTPServer**：
```bash
curl http://localhost:9090/metrics | grep "sdt_"
```

预期输出类似：
```
# HELP sdt_connections_active Current number of active client connections
# TYPE sdt_connections_active gauge
sdt_connections_active 1.0
# HELP sdt_connections_total Total number of connections since startup
# TYPE sdt_connections_total counter
sdt_connections_total 5.0
# HELP sdt_commands_total Total commands processed
# TYPE sdt_commands_total counter
sdt_commands_total{command="COM_QUERY"} 42.0
sdt_commands_total{command="COM_PING"} 12.0
...
```
