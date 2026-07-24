# SQL Dialect Translator 对接手册

> 本文档详细说明如何在实际项目中集成 SQL Dialect Translator 中间件。
> 📖 快速上手请先阅读 [README](../README.md)。

---

## 目录

1. [Maven 依赖配置](#1-maven-依赖配置)
2. [JDBC URL 格式详解](#2-jdbc-url-格式详解)
3. [Spring Boot 集成](#3-spring-boot-集成)
4. [程序化调用 API](#4-程序化调用-api)
5. [支持的 SQL 函数映射表](#5-支持的-sql-函数映射表)
6. [方言标识符一览](#6-方言标识符一览)
7. [已知限制与边界](#7-已知限制与边界)
8. [测试指南](#8-测试指南)
9. [常见问题](#9-常见问题)

---

## 1. Maven 依赖配置

### 基础依赖

```xml
<dependencies>
    <!-- 翻译引擎核心 -->
    <dependency>
        <groupId>com.draven.sql.translator</groupId>
        <artifactId>sdt-core</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>

    <!-- JDBC Wrapper 驱动（可选，仅 JDBC 方式需要） -->
    <dependency>
        <groupId>com.draven.sql.translator</groupId>
        <artifactId>sdt-jdbc</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

### 构建快照版本

由于当前为 SNAPSHOT 版本，需要在 `pom.xml` 中添加仓库配置（或先在本地构建）：

```bash
# 本地构建安装
git clone <repo-url>
cd sql-dialect-translator
mvn clean install -DskipTests
```

---

## 2. JDBC URL 格式详解

### URL 规范

```
jdbc:translator:<源方言标识符>:<目标数据库子协议>:<真实连接地址>[?参数]
```

- **源方言标识符**：`mysql`、`oracle`、`sqlserver`、`postgresql`
- **目标数据库子协议**：同时用于构造最终 JDBC URL 前缀和通过注册表查找方言组
  - 默认前缀：`jdbc:<子协议>:`，用户地址部分自带 `://`
  - Oracle 特殊前缀：`jdbc:oracle:thin:@`（`@` 已含，用户地址部分不带）
  - 常用子协议：`highgo`→PG方言组, `postgresql`→PG方言组, `mysql`→MySQL方言组, `oracle`→Oracle方言组, `dm`→Oracle方言组

### Oracle JDBC URL 格式适配

Oracle Thin 驱动支持多种连接格式，前缀统一为 `jdbc:oracle:thin:@`。
`@` 由前缀提供，用户在地址部分直接写后面的内容：

| 格式 | translator URL | 最终 realUrl |
|------|---------------|-------------|
| Service Name（推荐） | `jdbc:translator:mysql:oracle://host:1521/XE` | `jdbc:oracle:thin:@//host:1521/XE` |
| SID（旧式） | `jdbc:translator:mysql:oracle:host:1521:ORCL` | `jdbc:oracle:thin:@host:1521:ORCL` |
| TNS Alias | `jdbc:translator:mysql:oracle:TNSName` | `jdbc:oracle:thin:@TNSName` |
| TNS Descriptor | `jdbc:translator:mysql:oracle:(DESCRIPTION=...)` | `jdbc:oracle:thin:@(DESCRIPTION=...)` |

### 示例

| 用途 | JDBC URL |
|------|----------|
| 应用写 MySQL SQL → 访问 PostgreSQL | `jdbc:translator:mysql:postgresql://localhost:5432/mydb` |
| 应用写 MySQL SQL → 访问 HighGo（瀚高） | `jdbc:translator:mysql:highgo://localhost:5866/highgo` |
| 应用写 MySQL SQL → 访问 Oracle (Service Name) | `jdbc:translator:mysql:oracle://localhost:1521/XE` |
| 应用写 MySQL SQL → 访问 Oracle (SID) | `jdbc:translator:mysql:oracle:localhost:1521:ORCL` |
| 应用写 Oracle SQL → 访问 MySQL | `jdbc:translator:oracle:mysql://localhost:3306/mydb` |
| 应用写 SQL Server SQL → 访问 PostgreSQL | `jdbc:translator:sqlserver:postgresql://localhost:5432/mydb` |

### 驱动注册

驱动通过 SPI 自动注册。只需确保 `sdt-jdbc.jar` 在 classpath 中，
`DriverManager.getConnection()` 会自动识别以 `jdbc:translator:` 开头的 URL。

如需手动注册：

```java
// 方式一：类加载触发 static 注册块
Class.forName("com.translator.jdbc.TranslatorDriver");

// 方式二：显式注册（static 块已自动完成）
DriverManager.registerDriver(new TranslatorDriver());
```

### 连接参数透传

JDBC URL 中的查询参数和 `Properties` 对象中的属性会原样传递给底层真实驱动。

```java
Properties props = new Properties();
props.setProperty("user", "dbuser");
props.setProperty("password", "dbpass");
props.setProperty("ssl", "true");

Connection conn = DriverManager.getConnection(
    "jdbc:translator:mysql:postgresql://localhost:5432/mydb?ssl=true",
    props
);
```

---

## 3. Spring Boot 集成

### application.yml 配置

```yaml
spring:
  datasource:
    # 应用写 MySQL 方言 SQL → 访问 PostgreSQL
    url: jdbc:translator:mysql:postgresql://localhost:5432/mydb
    username: dbuser
    password: dbpass
    driver-class-name: com.translator.jdbc.TranslatorDriver
    # 真实 PG 驱动的连接池配置不变
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
```

### 多数据源示例

```yaml
spring:
  datasource:
    primary:
      # 源 = MySQL, 目标 = PostgreSQL
      url: jdbc:translator:mysql:postgresql://pg-host:5432/db1
      driver-class-name: com.translator.jdbc.TranslatorDriver
      username: user1
      password: pass1
    secondary:
      # 源 = Oracle, 目标 = MySQL
      url: jdbc:translator:oracle:mysql://mysql-host:3306/db2
      driver-class-name: com.translator.jdbc.TranslatorDriver
      username: user2
      password: pass2
```

### 注意事项

1. **连接池兼容**：HikariCP、Druid 等连接池可以直接包装 `TranslatorConnection`，无需额外配置。
2. **事务管理**：`@Transactional` 正常工作，事务由底层真实数据库驱动管理。
3. **ORM 框架**：MyBatis、JPA/Hibernate 可直接使用。通过 `nativeSQL` 或直接写 SQL 的查询会自动翻译。
4. **输出大小写控制**：通过 URL 查询参数 `keywordCase` 和 `identifierCase` 控制翻译后 SQL 的大小写，见下文。

### 通过 URL 参数控制输出大小写

JDBC URL 支持两个可选查询参数，无需修改代码即可控制翻译后 SQL 的大小写：

| 参数 | 可选值 | 默认值 | 说明 |
|------|--------|--------|------|
| `keywordCase` | `UPPER`, `LOWER` | `UPPER` | 关键词（SELECT/WHERE/AND 等）大小写 |
| `identifierCase` | `UPPER`, `LOWER`, `UNCHANGED` | `LOWER` | 标识符（表名/列名）大小写 |

**URL 示例：**

| JDBC URL | 效果 |
|----------|------|
| `jdbc:translator:mysql:postgresql://host:5432/db` | 默认：`SELECT "category" FROM "table"` |
| `jdbc:translator:mysql:postgresql://host:5432/db?keywordCase=LOWER&identifierCase=UPPER` | `select "CATEGORY" from "TABLE"` |
| `jdbc:translator:mysql:postgresql://host:5432/db?identifierCase=UNCHANGED` | `SELECT "category" FROM "table"` |

**Spring Boot 完整示例：**

```yaml
spring:
  datasource:
    # 关键词小写、标识符大写
    url: jdbc:translator:mysql:postgresql://localhost:5432/mydb?keywordCase=LOWER&identifierCase=UPPER
    driver-class-name: com.translator.jdbc.TranslatorDriver
    username: dbuser
    password: dbpass
    hikari:
      maximum-pool-size: 10
```

---

## 4. 程序化调用 API

### SqlTranslator — 核心翻译 API

```java
// 方式一：使用方言标识符字符串
String result = SqlTranslator.translate(
    "SELECT IFNULL(name, 'unknown') FROM users",
    "mysql",         // 源方言
    "postgresql"     // 目标方言
);

// 方式二：使用 DialectType 枚举
String result = SqlTranslator.translate(
    "SELECT NVL(name, 'N/A'), SYSDATE() FROM dual",
    DialectType.ORACLE,
    DialectType.POSTGRESQL
);
// → SELECT COALESCE(name, 'N/A'), CURRENT_TIMESTAMP FROM dual
```

### SqlTranslator — 带配置翻译（控制大小写）

输出 SQL 的关键词、表名、字段名的大小写可通过 `TranslationConfig` 灵活控制：

```java
import com.translator.core.config.TranslationConfig;

// 场景 1：关键词大写 + 标识符小写（PostgreSQL/Highgo 推荐，默认行为）
TranslationConfig pgConfig = new TranslationConfig()
    .withKeywordCase(TranslationConfig.KeywordCase.UPPER)
    .withIdentifierCase(TranslationConfig.IdentifierCase.LOWER);

String result1 = new SqlTranslator(
    DialectType.MYSQL,
    DialectType.POSTGRESQL,
    pgConfig
).translate("SELECT category FROM `table` WHERE category != ''");
// → SELECT "category" FROM "table" WHERE "category" <> ''

// 场景 2：关键词小写 + 标识符大写
TranslationConfig upperConfig = new TranslationConfig()
    .withKeywordCase(TranslationConfig.KeywordCase.LOWER)
    .withIdentifierCase(TranslationConfig.IdentifierCase.UPPER);

String result2 = SqlTranslator.translate(
    "SELECT category FROM `table`",
    DialectType.MYSQL,
    DialectType.POSTGRESQL,
    upperConfig
);
// → select "CATEGORY" from "TABLE"

// 场景 3：标识符保持原始大小写（不转换）
TranslationConfig unchangedConfig = new TranslationConfig()
    .withIdentifierCase(TranslationConfig.IdentifierCase.UNCHANGED);
```

### 配置项说明

| 配置 | 枚举值 | 说明 |
|------|--------|------|
| `keywordCase` | `UPPER` | 关键词大写输出（默认） |
| `keywordCase` | `LOWER` | 关键词小写输出 |
| `identifierCase` | `UPPER` | 标识符（表名/列名）转大写后加引号 |
| `identifierCase` | `LOWER` | 标识符转小写后加引号（默认） |
| `identifierCase` | `UNCHANGED` | 标识符保持原始大小写后加引号 |

### SqlTranslator — 实例化使用

```java
// 创建翻译器实例（推荐：可复用，线程安全）
SqlTranslator translator = new SqlTranslator(
    DialectType.MYSQL,
    DialectType.POSTGRESQL
);

// 多次调用
String sql1 = translator.translate("SELECT IFNULL(a, 0) FROM t1");
String sql2 = translator.translate("SELECT NOW() FROM t2");
```

### JDBC 直接使用

```java
// 1. 获取连接
Connection conn = DriverManager.getConnection(
    "jdbc:translator:mysql:postgresql://localhost:5432/mydb",
    "user", "password"
);

// 2. Statement — SQL 自动翻译
Statement stmt = conn.createStatement();
stmt.execute("INSERT INTO `users` (`name`) VALUES ('Alice')");
// 实际执行: INSERT INTO "users" ("name") VALUES ('Alice')

// 3. PreparedStatement — SQL 翻译 + 参数透传
PreparedStatement pstmt = conn.prepareStatement(
    "SELECT `name` FROM `users` WHERE `id` = ?"
);
pstmt.setInt(1, 42);
ResultSet rs = pstmt.executeQuery();
// 实际执行: SELECT "name" FROM "users" WHERE "id" = 42

// 4. 查看翻译结果（不执行）
String nativeSql = conn.nativeSQL("SELECT IFNULL(`a`, 0) FROM `t`");
// → "SELECT COALESCE("a", 0) FROM "t""

// 5. 同方言连接 — 零开销
Connection sameConn = DriverManager.getConnection(
    "jdbc:translator:postgresql:postgresql://localhost:5432/mydb",
    "user", "password"
);
// 源 = 目标时，SQL 原样透传，无解析开销
```

---

## 5. 支持的 SQL 函数映射表

### 空值处理函数

| 源函数 | 源方言 | 目标函数 | 目标方言 |
|--------|--------|----------|----------|
| `IFNULL(a, b)` | MySQL | `COALESCE(a, b)` | PG / Oracle |
| `NVL(a, b)` | Oracle | `COALESCE(a, b)` | PG / MySQL |
| `ISNULL(a, b)` | SQL Server | `COALESCE(a, b)` | PG / MySQL / Oracle |

### 日期时间函数

| 源函数 | 源方言 | 目标函数 | 目标方言 |
|--------|--------|----------|----------|
| `NOW()` | MySQL | `CURRENT_TIMESTAMP` | PG / Oracle |
| `GETDATE()` | SQL Server | `CURRENT_TIMESTAMP` | PG / MySQL / Oracle |
| `SYSDATE()` | Oracle | `CURRENT_TIMESTAMP` | PG / MySQL |

### 条件逻辑函数

| 源函数 | 源方言 | 目标语法 | 说明 |
|--------|--------|----------|------|
| `DECODE(expr, s1, r1, ..., d)` | Oracle | `CASE expr WHEN s1 THEN r1 ... ELSE d END` | 支持可选默认值 |

### SQL Server 特殊语法

| 源语法 | 目标语法 | 说明 |
|--------|----------|------|
| `SELECT TOP n ...` | `SELECT ... LIMIT n` | 预处理转换，支持 WITH ORDER BY |

### 标识符引用规则

| 源样式 | 示例 | 目标样式 | 示例 |
|--------|------|----------|------|
| 反引号 (MySQL) | `` `users` `` | 双引号 (PG/Oracle) | `"users"` |
| 方括号 (SQL Server) | `[users]` | 双引号 (PG/Oracle) | `"users"` |
| 无引号（`alias.column`） | `u.name` | 双引号 | `"u"."name"` |
| 无引号（独立标识符） | `category` | 双引号 | `"category"` |
| SQL 关键字 | `SELECT` | 无引号（不转） | `SELECT` |
| 函数名 | `IFNULL(...)` | 无引号（不转） | `COALESCE(...)` |

> **注意**：未引用的独立标识符（如表名、列名）会自动补双引号，并按照 `TranslationConfig.IdentifierCase` 配置转换大小写。
> 这有效避免了 PostgreSQL/Highgo 中双引号标识符大小写敏感导致的 `column "CATEGORY" does not exist` 错误。

### 分页语法

Calcite 内置的 `SqlDialect` 自动处理以下分页语法互转：

| 语法 | 数据库 |
|------|--------|
| `LIMIT n OFFSET m` | MySQL, PostgreSQL, SQLite |
| `OFFSET m ROWS FETCH NEXT n ROWS ONLY` | Oracle 12c+, SQL Server 2012+ |
| `ROWNUM <= n` (WHERE 中) | Oracle 11g 及更早（暂不支持自动转换） |

---

## 6. 方言标识符一览

| 方言组 | 标识符 | 包含数据库 |
|--------|--------|-----------|
| PostgreSQL | `postgresql` | PostgreSQL, HighGo, KingbaseES, PolarDB PG, Greenplum, TimescaleDB, PostGIS, IvorySQL, QuestDB, KaiwuDB |
| MySQL | `mysql` | MySQL, Doris, PolarDB MySQL |
| Oracle | `oracle` | Oracle, Oracle RAC, Oracle Spatial, DM(达梦) |
| SQL Server | `sqlserver` | SQL Server, Microsoft SQL Server |

### 方言注册表使用

```java
// 根据数据库产品名获取方言组
DialectType dialect = DialectRegistry.getDialectForProduct("KingbaseES");
// → DialectType.POSTGRESQL

// 获取方言组下的所有产品
List<String> products = DialectRegistry.getProductsForDialect(DialectType.MYSQL);
// → ["MySQL", "Doris", "PolarDB MySQL"]

// 获取所有方言组
Set<DialectType> allDialects = DialectRegistry.getAllDialects();
```

---

## 7. 已知限制与边界

### SQL 解析限制

- **存储过程和函数定义**：不支持解析 `CREATE PROCEDURE` / `CREATE FUNCTION`
- **复杂 DDL**：不支持 `ALTER TABLE`、`CREATE INDEX` 等
- **窗口函数**：`ROW_NUMBER() OVER (...)` 原样透传，不转换
- **递归 CTE**：`WITH RECURSIVE` 原样透传
- **数据库专有语法**：PG 的 `ON CONFLICT DO UPDATE`、Oracle 的 `CONNECT BY`、MySQL 的 `ON DUPLICATE KEY UPDATE` 原样透传

### 性能说明

- SQL 翻译耗时通常在 **1～5ms** 以内（取决于 SQL 复杂度）
- 同方言连接（源=目标）**零额外开销**，SQL 原样透传
- `PreparedStatement` 每次 `prepareStatement` 调用时翻译一次 SQL，后续 `executeQuery` / `executeUpdate` 复用翻译结果

### 数据类型映射

当前版本基础数据类型直接透传，不做映射。Calcite 的 `SqlDialect` 处理类型关键字的大小写和引用差异。

---

## 8. 测试指南

### 运行单元测试

```bash
# 运行全部单元测试（47+ 个，无需外部依赖）
mvn test
```

### 运行集成测试

集成测试基于 Testcontainers，需要 Docker Desktop 环境。

```bash
# 运行集成测试
mvn test -Dtest=TranslatorDriverIntegrationTest
```

集成测试会：
1. 启动 PostgreSQL 16 Alpine 容器
2. 创建测试表并插入数据
3. 通过 TranslatorDriver 发送 MySQL 方言 SQL
4. 验证翻译后的 SQL 在 PG 上正确执行

### 覆盖的测试场景

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `DialectTypeTest` | 4 | 标识符解析、异常 |
| `DialectRegistryTest` | 4 | 产品-方言映射 |
| `SqlTranslatorTest` | 47 | SELECT/DML/函数/分页/标识符/大小写配置/独立标识符/方言组合 |
| `JdbcUrlParserTest` | 6 | URL 解析、异常 |
| `TranslatorDriverIntegrationTest` | 9 | 端到端 JDBC 翻译（需 Docker） |

---

## 9. 常见问题

### Q: 应用已有大量 SQL，是否需要逐条修改？

**不需要。** 只需更换 JDBC URL 和驱动类，所有通过 `Statement.execute()` / `PreparedStatement` 执行的 SQL 会自动翻译。
MyBatis 的 XML 映射文件中的 SQL、JPA 的 `@Query` 注解中的 SQL 都不需要修改。

### Q: SQL 翻译失败时怎么办？

翻译异常以 `SqlTranslationException` 抛出（继承自 `RuntimeException`）。
应用可捕获此异常并执行降级逻辑：

```java
try {
    ResultSet rs = stmt.executeQuery(sql);
} catch (SqlTranslationException e) {
    log.error("SQL 翻译失败，使用原始 SQL 直接执行", e);
    // 降级：直接发给目标数据库（可能语法不兼容）
}
```

### Q: 如何扩展新的方言组？

见 [方言注册表](src/main/java/com/translator/core/DialectRegistry.java) 示例。
新增方言只需两步：
1. 在 `DialectType` 枚举中新增类型
2. 在 `DialectRegistry` 的 `static` 块中注册产品映射
3. 在 `SqlDialectFactory` 中添加对应的 Calcite `SqlDialect`
4. 在 `SqlTranslator.parseSql()` 中添加新的 parser 配置

### Q: 连接池配置有什么注意事项？

无特殊要求。HikariCP、Druid、Tomcat JDBC Pool 等连接池可直接使用。
连接池验证查询（如 `SELECT 1`）也会被翻译，建议使用目标方言的验证查询。

### Q: 驱动类需要配置在连接池中吗？

需要。连接池需要知道使用哪个驱动类：

```
driver-class-name: com.translator.jdbc.TranslatorDriver
```

真实数据库的驱动类（如 `org.postgresql.Driver`）由 TranslatorDriver 内部自动获取。

---

> 如有其他问题，请提交 Issue 或联系项目维护者。
