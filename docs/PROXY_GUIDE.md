# SDT Proxy 使用手册

> SDT Proxy（简称 **SDTP**）是一个 MySQL 协议代理。它对外伪装成 MySQL 5.7 服务端，接收客户端的 MySQL 协议请求，将 SQL 翻译为目标数据库方言后执行，结果以 MySQL 协议格式返回。客户端无需修改任何代码或驱动。

---

## 目录

1. [快速开始](#1-快速开始)
2. [配置文件](#2-配置文件)
3. [配置热更新](#3-配置热更新)
4. [启动与管理](#4-启动与管理)
5. [SQL 翻译配置](#5-sql-翻译配置)
6. [直通模式](#6-直通模式)
7. [系统变量](#7-系统变量)
8. [Docker 部署](#8-docker-部署)
9. [架构与线程模型](#9-架构与线程模型)
10. [常见问题](#10-常见问题)

---

## 1. 快速开始

### 1.1 构建

```bash
mvn clean package -DskipTests
```

产物在 `sdtp-server/target/`：
- `sdtp-server-*.zip` — Windows 分发包
- `sdtp-server-*.tar.gz` — Linux/macOS 分发包

### 1.2 解压并配置

```bash
tar -xzf sdtp-server-1.0.0-SNAPSHOT.tar.gz
cd sdtp-1.0.0-SNAPSHOT
vim config/proxy-config.yml
```

最小配置（连接 PostgreSQL）：

```yaml
proxy:
  port: 7788
  auth:
    user: root
    password: proxy_password

target:
  dialect: POSTGRESQL
  jdbc-url: jdbc:postgresql://192.168.1.100:5432/mydb
  username: pg_user
  password: pg_password
```

### 1.3 启动

```bash
# Linux / macOS
./bin/start.sh start        # 后台启动
./bin/start.sh start-fg     # 前台启动（Docker / systemd）

# Windows
bin\start.bat               # 前台启动
bin\start.bat start          # 后台启动
```

### 1.4 客户端连接

```bash
# 和连接普通 MySQL 完全一样
mysql -h 127.0.0.1 -P 7788 -u root -pproxy_password mydb

# 或 JDBC URL
jdbc:mysql://127.0.0.1:7788/mydb
```

---

## 2. 配置文件

完整配置项说明：

```yaml
proxy:
  # 监听端口（默认 7788）
  port: 7788

  # 客户端认证——客户端用此账密连接 Proxy
  auth:
    user: root
    password: proxy_password

# === 多后端模式（推荐） ===
# 按 name 区分不同后端数据库实例，客户端通过 USE <name> 切换
backends:
  - name: postgres_db          # 后端名称，客户端 USE 时使用
    dialect: POSTGRESQL
    jdbc-url: jdbc:postgresql://主机:5432/数据库
    username: pg_user
    password: pg_password
    max-pool-size: 20
    min-idle: 2

  - name: oracle_db            # 可配置多个后端
    dialect: ORACLE
    jdbc-url: jdbc:oracle:thin:@主机:1521:SID
    username: ora_user
    password: ora_password
    max-pool-size: 10
    min-idle: 2

# === 单后端模式（向后兼容） ===
# 如果不使用 backends 列表，可以用 target 段
target:
  name: mydb                   # 可选，默认从 jdbc-url 提取
  dialect: POSTGRESQL
  jdbc-url: jdbc:postgresql://localhost:5432/mydb
  username: pg_user
  password: pg_password
  max-pool-size: 20
  min-idle: 2

# SQL 翻译配置（仅 dialect ≠ MYSQL 时生效）
translation:
  keyword-case: UPPER        # UPPER | LOWER
  identifier-case: LOWER     # LOWER | UPPER | UNCHANGED

# 配置热更新参数（可选，均有默认值）
reload:
  queue-size: 1000            # 热 reload 等待队列容量，默认 1000
  drain-timeout-ms: 30000     # drain 超时（毫秒），默认 30000
  debounce-ms: 500            # 文件变化防抖间隔（毫秒），默认 500
```

### 2.1 多后端模式下的事务与切换数据库原则

在多后端模式下，若客户端正在开启事务的会话状态中（例如：执行了 `SET autocommit=0;` 或当前事务中包含未提交的数据变更），一旦其执行了切换数据库的操作（无论是发送 `USE <dbname>;` 语句，还是通过协议底层的 `COM_INIT_DB` 命令）：

1. **隐式自动回滚**：SDT Proxy 将自动拦截该切换请求，如果检测到当前会话中原数据库后端上存在未提交的活跃事务（即 Netty Channel 属性上绑定了物理事务连接），会在原物理连接上执行隐式的 `rollback`，然后将物理连接释放并归还给原后端的 HikariCP 连接池。
2. **安全切换**：在清理完旧后端的事务状态后，Proxy 会将当前 session 的 database 切换至目标新数据库。客户端在新库上发送的下一个 SQL 将会从新数据库的连接池获取物理连接并正常执行。

> 💡 **设计目的**：这种隐式回滚机制能够防止因为客户端在切换数据库后自动发送的某些不兼容 MySQL 元数据查询，导致底层 PostgreSQL 等物理事务连接被置为 aborted（错误码 `25P02`）进而引发后续查询连续失败，同时也彻底避免了跨库事务引起的连接泄漏。

### 2.2 通过环境变量覆盖配置文件路径

```bash
export SDTP_CONFIG=/path/to/my-config.yml
./bin/start.sh start
```

---

## 3. 配置热更新

SDT Proxy 支持运行时自动检测配置文件变更，并**零停机**更新后端数据库配置。

### 3.1 支持的热更新范围

| 配置项 | 热更新 | 说明 |
|--------|--------|------|
| `backends` 列表（新增后端） | ✅ | 直接创建连接池并加入路由 |
| `backends` 列表（删除后端） | ✅ | 优雅 drain 后关闭连接池 |
| `backends` 列表（修改后端） | ✅ | drain → 重建连接池 → 恢复路由 |
| `proxy.port` | ❌ | 需重启生效 |
| `proxy.auth` | ❌ | 需重启生效 |
| `translation`（全局） | ❌ | 需重启生效 |
| `reload.*` | ❌ | 需重启生效 |

> **注意**：仅 `backends` 列表中的条目支持热更新。proxy 端口、认证信息、全局翻译配置变更后会在日志中打印 WARN 提示，但**不会自动生效**，需要手动重启。

### 3.2 工作原理

```
配置文件变更
    │
    ▼
┌──────────────────────┐
│  WatchService        │  监听文件目录，检测 ENTRY_MODIFY 事件
│  (防抖 500ms)        │  避免编辑器多次保存触发多次 reload
└────────┬─────────────┘
         │
         ▼
┌──────────────────────┐
│  重新加载 YAML       │  ConfigLoader 解析最新配置
│  差异对比            │  按后端 name 匹配新旧列表
└────────┬─────────────┘
         │
    ┌────┼────┐
    ▼    ▼    ▼
  新增  删除  变更
    │    │    │
    │    │    └── drain 旧连接池 → 创建新连接池 → 恢复路由
    │    │
    │    └── markDraining → 等 in-flight 完成 → 关闭连接池 → 移除
    │
    └── 创建连接池 → 加入路由表
```

### 3.3 三种变更场景详解

#### 场景 A：新增后端

在 `backends` 列表中添加一个**新 name** 的条目，保存文件后：

```yaml
backends:
  - name: postgres_db        # 原有
    dialect: POSTGRESQL
    jdbc-url: jdbc:postgresql://10.0.0.1:5432/mydb
    # ...

  - name: oracle_db          # ← 新增，name 之前不存在
    dialect: ORACLE
    jdbc-url: jdbc:oracle:thin:@10.0.0.2:1521:ORCL
    # ...
```

- **对客户端无影响**：已有连接不受影响，新连接可通过 `USE oracle_db` 切换到新后端。
- **耗时**：约 1~3 秒（HikariCP 初始化连接池）。

#### 场景 B：删除后端

从 `backends` 列表中移除某个条目，保存文件后：

```yaml
backends:
  - name: postgres_db        # 保留
    # ...
  # - name: oracle_db        # ← 删除这整个条目
  #   ...
```

- **优雅关闭**：
  1. 标记该后端为 DRAINING 状态
  2. 等待正在执行的请求完成（最长 30 秒）
  3. 关闭 HikariCP 连接池
  4. 从路由表移除
- **新请求**：收到 `ERR 1053 "Server shutdown in progress, backend 'oracle_db' is being removed"`，客户端应重试。
- **已连接客户端**：`USE oracle_db` 后执行 SQL 会路由到默认后端（列表第一个）。

#### 场景 C：修改后端配置

修改某个**已有 name** 的条目的连接信息或连接池参数，保存文件后：

```yaml
backends:
  - name: postgres_db
    dialect: POSTGRESQL
    jdbc-url: jdbc:postgresql://10.0.0.3:5432/newdb   # ← 改了 IP 和库名
    username: new_user                                  # ← 改了用户名
    password: new_password                              # ← 改了密码
    max-pool-size: 30                                   # ← 改了池大小
```

**热 reload 流程**（零停机）：

1. **RELOADING 阶段**（~500ms~30s）
   - 该后端进入 RELOADING 状态
   - **新请求**：放入等待队列（默认容量 1000）
   - **正在执行的请求**：继续执行直到完成
2. **切换阶段**（< 1s）
   - 等待所有 in-flight 请求完成（最长 `drain-timeout-ms`）
   - 关闭旧 HikariCP 连接池
   - 按新配置创建 HikariCP 连接池
   - 原子切换到新连接池
3. **恢复阶段**
   - 队列中的请求在新连接池上逐条执行
   - 新请求直接路由到新连接池

> ⚠️ **队列满**：如果在 RELOADING 期间等待队列达到上限（`queue-size`），后续请求会收到错误：
> ```
> ERR 1053 (HY000): Server shutdown in progress, backend 'xxx' is reloading, please retry later
> ```
> 客户端应捕获此错误并稍后重试。

### 3.4 reload 配置项

在配置文件中添加 `reload` 段（可选，所有字段均有默认值）：

```yaml
reload:
  # 热 reload 期间请求等待队列的最大长度（默认 1000）
  # 队列满后新请求直接返回 1053 错误
  queue-size: 1000

  # 等待 in-flight 请求完成的最长时间，毫秒（默认 30000）
  # 超时后强制关闭旧连接池，队列中未处理请求返回错误
  drain-timeout-ms: 30000

  # 文件变化防抖间隔，毫秒（默认 500）
  # 在此时间内多次保存只触发一次 reload
  debounce-ms: 500
```

### 3.5 配置文件位置

Watcher 监听的配置文件与启动时加载的**同一个文件**，查找顺序：

| 优先级 | 路径 | 说明 |
|--------|------|------|
| 1 | 系统属性 `-Dproxy.config=/path/to/config.yml` | 推荐生产环境使用 |
| 2 | classpath 下的 `proxy-config.yml` | **不支持热更新**（jar 内文件不可监听） |
| 3 | 当前目录下的 `proxy-config.yml` | 开发环境默认 |

> ⚠️ **注意**：如果配置是从 classpath 加载的（jar 内文件），watcher 会自动禁用，启动日志会显示：
> ```
> ConfigWatcher disabled: using classpath or defaults
> ```
> 需要热更新功能时，请使用外部配置文件并通过 `-Dproxy.config` 指定。

### 3.6 验证热更新

1. 启动 Proxy 后查看日志，确认 watcher 已启动：
   ```
   ConfigWatcher started, watching: /path/to/proxy-config.yml
   ```

2. 修改配置文件中的 backend 信息，保存。

3. 查看日志确认 reload 过程：
   ```
   Detected change: ENTRY_MODIFY on proxy-config.yml
   Reloading config from: /path/to/proxy-config.yml
   Backend 'postgres_db' changed (jdbcUrl=old → new, pool=20 → 30)
   Backend 'postgres_db': starting drain (timeout=30000ms)
   Backend 'postgres_db': drain complete (0 remaining in-flight)
   Backend 'postgres_db': new delegate activated, draining 5 queued requests
   Backend 'postgres_db': queued requests drained
   Backend 'postgres_db' reloaded (drained=true)
   ```

4. 从客户端验证：
   ```sql
   -- 如果是新增的 backend
   USE new_backend_name;
   SELECT 1;  -- 应正常返回
   ```

### 3.7 故障处理

| 情况 | 行为 |
|------|------|
| YAML 语法错误 | 记录 ERROR 日志，**保持当前配置不变** |
| drain 超时 | 记录 WARN，强制关闭旧连接池，队列中请求返回错误 |
| 新配置连接失败（如 JDBC URL 错误） | 后端进入 ACTIVE 但执行 SQL 时会报连接错误 |
| 配置文件被删除 | 记录 ERROR，保持当前配置不变（不删除任何后端） |

---

## 4. 启动与管理

### Linux / macOS

| 命令 | 说明 |
|------|------|
| `./bin/start.sh start` | 后台启动（nohup），PID 写入 `sdtp.pid` |
| `./bin/start.sh start-fg` | 前台启动，日志输出到控制台 |
| `./bin/start.sh stop` | 优雅停止（SIGTERM，30s 超时后 SIGKILL） |
| `./bin/start.sh restart` | 重启 |
| `./bin/start.sh status` | 查看运行状态 |

### Windows

| 命令 | 说明 |
|------|------|
| `bin\start.bat` | 前台启动（双击即可） |
| `bin\start.bat start` | 后台启动（javaw） |
| `bin\start.bat stop` | 停止 |
| `bin\start.bat status` | 查看状态 |

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SDTP_CONFIG` | `config/proxy-config.yml` | 配置文件路径 |
| `JAVA_HOME` | 系统 PATH 中的 java | JDK 安装目录 |
| `JAVA_OPTS` | `-Xms256m -Xmx1024m -XX:+UseG1GC` | JVM 参数 |

### 日志

- **控制台**：前台模式直接输出，后台模式重定向到 `logs/console.log`
- **文件**：`logs/proxy.log`，每天滚动，保留 30 天
- 可通过 `logback.xml`（jar 内 `logback.xml`）自定义

---

## 5. SQL 翻译配置

TranslationConfig 控制翻译后 SQL 的关键词和标识符大小写。

### 配置项

```yaml
translation:
  keyword-case: UPPER      # UPPER（默认）| LOWER
  identifier-case: LOWER   # LOWER（默认）| UPPER | UNCHANGED
```

### 各数据库推荐配置

| 目标数据库 | keyword-case | identifier-case | 原因 |
|-----------|-------------|----------------|------|
| PostgreSQL / HighGo | `UPPER` | `LOWER` ✅ | PG 自动将未引用标识符转小写 |
| Oracle / DM | `UPPER` | `UPPER` | Oracle 默认标识符大写 |
| SQL Server | `UPPER` | `UNCHANGED` | SQL Server 保留原始大小写 |
| MySQL | — | — | 翻译自动禁用（源=目标） |

### 示例

```yaml
# PostgreSQL 目标
translation:
  keyword-case: UPPER
  identifier-case: LOWER
```

输入：
```sql
SELECT Name, CreatedAt FROM Users WHERE Status != 'deleted'
```

输出（发往 PostgreSQL）：
```sql
SELECT "name", "createdat" FROM "users" WHERE "status" <> 'deleted'
```

---

## 6. 直通模式

某些 SQL 不需要翻译（如目标库专有函数、性能分析语句），可以在 SQL 开头加注释标记跳过翻译引擎。

### 标记格式

| 格式 | 示例 |
|------|------|
| `-- direct` | `-- direct\nSELECT pg_sleep(1)` |
| `-- sdtp:direct` | `-- sdtp:direct\nSELECT now()` |
| `/* direct */` | `/* direct */ SELECT version()` |
| `/* sdtp:direct */` | `/* sdtp:direct */ EXPLAIN ANALYZE SELECT ...` |

### 行为

1. 检测到标记 → **剥离标记注释** → 原 SQL 直通目标库
2. 未检测到 → 正常走 Calcite 翻译
3. 标记大小写不敏感：`-- DIRECT`、`/* SDTP:DIRECT */` 均可
4. 前导空格不影响

### 典型场景

```sql
-- PostgreSQL 专有函数，不需要翻译
-- direct
SELECT pg_backend_pid(), pg_postmaster_start_time()

-- 执行计划分析
/* direct */ EXPLAIN ANALYZE SELECT * FROM large_table WHERE id > 1000

-- 目标库特有的系统函数
-- sdtp:direct
SELECT oid, relname FROM pg_class WHERE relkind = 'r'
```

---

## 7. 系统变量

Proxy 拦截常见 MySQL 系统变量查询并返回伪造值，不会转发到目标库。

| 变量 | 返回值 |
|------|--------|
| `@@version` | `5.7.38-proxy` |
| `@@version_comment` | `MySQL Proxy 5.7.38` |
| `@@character_set_client` | `utf8mb4` |
| `@@autocommit` | `1` |
| `@@tx_isolation` | `READ-COMMITTED` |
| `@@max_allowed_packet` | `16777216` |
| `SELECT DATABASE()` | 当前选择的 database |

同时拦截 `SET NAMES`、`SET autocommit` 等命令并在 Proxy 侧直接返回 OK。

---

## 8. Docker 部署

```bash
# 一键启动（Proxy + 数据库服务组）
docker compose -f docker/docker-compose.yml up -d

# 查看日志
docker logs -f sdtp-proxy

# 客户端连接
mysql -h 127.0.0.1 -P 7788 -u root -pproxy_password
```

`docker-compose.yml`（位于 `docker/` 目录）包含：
- **PostgreSQL 15** 目标库（端口 5432）
- **MySQL 8.0** 目标库（端口 33066）
- **Oracle 23c Free** 目标库（端口 1521）
- **SQL Server 2022** 目标库（端口 1433）
- **IvorySQL 3.1** 目标库（端口 5435，兼容 Oracle 协议的 PostgreSQL 分支）
- **达梦数据库 (dm8)** 目标库（端口 5236，Oracle 方言组国产数据库）
- **Apache Doris 2.0.0** 目标库（端口 9030，MySQL 协议分析型数据库）
- **SDT Proxy**（端口 7788）
- **init-drivers**（动态自动从阿里云镜像源下载上述所有的数据库 JDBC 驱动包并挂载给 Proxy 代理）

Docker 专用配置：`sdtp-server/src/main/resources/proxy-config-docker.yml`

---

## 9. 架构与线程模型

### 模块结构

```
sdtp-protocol     MySQL 线协议：Packet 定义、Decoder、Encoder、BufferUtils、认证
sdtp-core         会话管理、HandshakeHandler、AuthHandler、CommandHandler、系统变量拦截
sdtp-backend      TypeMapper、ResultSetEncoder、JdbcBackendQueryProcessor(HikariCP)
sdtp-server       ProxyBootstrap(Netty)、ConfigLoader(YAML)、分发包
```

### 线程模型

```
┌─ Boss EventLoop (1 thread) ─────────────────────┐
│  接受 TCP 连接                                    │
└──────────────────────┬───────────────────────────┘
                       │
┌─ Worker EventLoop (N threads) ──────────────────┐
│  MySQLPacketDecoder → Encoder                    │
│  HandshakeHandler → AuthHandler（IO 线程完成）     │
└──────────────────────┬───────────────────────────┘
                       │ auth 成功，pipeline 替换
┌─ Biz ExecutorGroup (M threads) ─────────────────┐
│  CommandHandler（非 IO 线程）                      │
│    ├─ 系统变量 → 伪造响应                          │
│    ├─ -- direct → 直通                            │
│    └─ 普通 SQL → TranslationQueryProcessor       │
│                   ├─ Calcite 翻译                 │
│                   └─ JdbcBackendQueryProcessor   │
│                       └─ HikariCP → JDBC 执行     │
└──────────────────────────────────────────────────┘
```

**关键原则**：IO 线程（EventLoop）只做协议解析和组装。JDBC 阻塞调用在 Biz ExecutorGroup 中执行，不会阻塞网络层。

---

## 10. 常见问题

查看日志 `logs/proxy.log`，搜索 `Translated:` 可看到翻译前后的 SQL 对比。

### Q: 某些 SQL 不想翻译怎么办？

使用[直通模式](#5-直通模式)，在 SQL 开头加 `-- direct`。

### Q: 如何修改日志级别？

修改 jar 内 `logback.xml`，或启动时指定：

```bash
JAVA_OPTS="-Dlog.level=DEBUG" ./bin/start.sh start
```

### Q: 连接池多大合适？

建议 `max-pool-size` 设为目标库 `max_connections` 的 50%~80%。默认 20 适用于中等并发。

### Q: 支持 SSL/TLS 吗？

当前版本暂不支持客户端 SSL 连接。

### Q: 性能如何？

单机可承载 10,000+ 并发连接（受限于 Netty IO 线程数），实际吞吐取决于目标库性能。翻译开销约 1-5ms/SQL。
