# SDT SQL 翻译示例服务 (Demos)

本目录包含两个基于 Spring Boot 2.7 (Java 8) 编写的、可直接运行的 Web 服务实例。它们用于演示 **SQL Dialect Translator (SDT)** 的两种核心工作模式：**JDBC Wrapper 驱动模式** 与 **SDT Proxy 代理模式**。

这两个服务都配置了内置的、极具现代感的交互式 SQL Playground 网页端。您可以在网页上直接输入 MySQL 语法 SQL，点击执行并实时查看返回的 JSON 结果。

---

## 目录指南
- **`./sdt-spring-boot-jdbc-demo`**：演示 **JDBC Wrapper 驱动模式**。
- **`./sdtp-spring-boot-demo`**：演示 **SDT Proxy 代理模式**。无需引入任何 SDT 相关 JAR，直接使用官方 MySQL 驱动连接 Proxy 代理。

---

## 环境准备

### 1. 启动底层数据库与 Proxy 代理
本示例依赖一个 PostgreSQL 实例作为目标存储数据库。Proxy 模式下还需要运行 Netty 代理服务端。
我们在项目 `docker` 目录提供了 `docker-compose.yml`，可以通过以下命令快速一键拉起所需环境（请确保 Docker 服务已启动）：
```bash
# 在项目根目录下执行
docker compose -f docker/docker-compose.yml up -d
```
这会拉起：
- **PostgreSQL**：监听本地 `5432` 端口，数据库名为 `mydb`，用户名 `sdtpu`，密码 `pg_password`。
- **SDT Proxy**：监听本地 `7788` 端口，后端指向该 Postgres 库。

### 2. 初始化测试数据（手动）
因为 Demo 中去除了自动建表的操作，为了使查询能正常运行，您需要在 PostgreSQL 数据库中创建 `demo_users` 测试表。

您可以使用任意 Postgres 客户端（如 pgAdmin、DBeaver）或者直接在命令行连接并执行以下 SQL 语句：
```sql
-- 连接 PostgreSQL 后，在 public 模式下执行：
CREATE TABLE IF NOT EXISTS demo_users (
    id INT PRIMARY KEY,
    name VARCHAR(100),
    age INT,
    email VARCHAR(100)
);

INSERT INTO demo_users (id, name, age, email) VALUES
(1, 'Alice', 25, 'alice@example.com'),
(2, 'Bob', 30, 'bob@example.com'),
(3, 'Charlie', 35, 'charlie@example.com')
ON CONFLICT (id) DO NOTHING;
```

### 3. 全局编译打包
在运行示例前，请在项目根目录下完成全量编译打包，使得 Demo 模块可以正确加载本地依赖包：
```bash
# 在项目根目录下执行
mvn clean package -DskipTests
```

---

## 运行与验证

### 实例 1：JDBC Wrapper 驱动模式 (`sdt-spring-boot-jdbc-demo`)
该模式下，Spring Boot 数据源配置了 `com.translator.jdbc.TranslatorDriver`。它在发送 SQL 时，会自动将其翻译成 PostgreSQL 方言执行，对业务代码完全透明。

1. 进入模块目录并启动 Spring Boot 应用：
   ```bash
   cd demo/sdt-spring-boot-jdbc-demo
   mvn spring-boot:run
   ```
2. 服务运行在 **`8081`** 端口。
3. 浏览器访问：[http://localhost:8081](http://localhost:8081)。
4. 在页面左侧文本框内输入 MySQL 语法（例如包含反引号或特定的 `IFNULL` 函数），点击“执行 SQL”即可在右侧看到翻译执行后的 JSON 响应。

---

### 实例 2：SDT Proxy 代理模式 (`sdtp-spring-boot-demo`)
该模式下，应用完全不需要引入任何 SDT 的专有 JAR 包或驱动。它只使用普通的 `mysql-connector-java` 驱动连接到本地的 `7788` 代理端口。

1. 确认第一步中的 `docker compose`（含 Proxy）已正常运行。
2. 进入模块目录并启动 Spring Boot 应用：
   ```bash
   cd demo/sdtp-spring-boot-demo
   mvn spring-boot:run
   ```
3. 服务运行在 **`8082`** 端口。
4. 浏览器访问：[http://localhost:8082](http://localhost:8082)。
5. 输入任意包含 MySQL 方言特性的 SQL，点击“执行 SQL”查看经过 Netty 代理翻译处理后返回的 JSON 结果。

---

## SQL 快速测试示例
您可以在任一 Playground 网页端上，通过内置的快捷按钮加载并测试以下 MySQL 语法的 SQL 语句：

1. **基本查询**（验证自动翻译）：
   ```sql
   SELECT * FROM demo_users ORDER BY id ASC
   ```
2. **反引号与过滤**（验证标识符去除与条件解析）：
   ```sql
   SELECT `id`, `name`, `age` FROM `demo_users` WHERE `age` >= 30
   ```
3. **方言函数转换**（验证 `IFNULL` 翻译为 `COALESCE`）：
   ```sql
   SELECT id, IFNULL(name, '无名氏') as user_name FROM demo_users
   ```
4. **分页语法转换**（验证 `LIMIT OFFSET` 翻译）：
   ```sql
   SELECT * FROM demo_users LIMIT 2 OFFSET 1
   ```
