package com.translator.demo.proxy.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.translator.core.DialectType;
import com.translator.core.SqlTranslator;

@RestController
@RequestMapping("/api/simulate")
public class SimulatorController {

    private static final Logger log = LoggerFactory.getLogger(SimulatorController.class);

    @Autowired
    private DataSource dataSource;

    // 内存中的审计队列
    private static final Queue<SqlAudit> auditQueue = new ConcurrentLinkedQueue<>();

    // 简单的全局统计变量，用原子锁
    private static final AtomicLong totalOrders = new AtomicLong(0);
    private static final AtomicLong totalRevenueCent = new AtomicLong(0); // 存分，避免浮点数精度

    private static final String[] BUYERS = {"张三", "李四", "王五", "赵六", "钱七", "孙八", "周九", "吴十"};

    // 审计结构实体
    public static class SqlAudit {
        private String timestamp;
        private String originalSql;
        private String translatedSql;
        private long durationMs;
        private boolean success;
        private String errorMessage;
        private int rowsAffected;

        public SqlAudit(
                String timestamp,
                String originalSql,
                String translatedSql,
                long durationMs,
                boolean success,
                String errorMessage,
                int rowsAffected) {
            this.timestamp = timestamp;
            this.originalSql = originalSql;
            this.translatedSql = translatedSql;
            this.durationMs = durationMs;
            this.success = success;
            this.errorMessage = errorMessage;
            this.rowsAffected = rowsAffected;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getOriginalSql() {
            return originalSql;
        }

        public String getTranslatedSql() {
            return translatedSql;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public int getRowsAffected() {
            return rowsAffected;
        }
    }

    // 辅助方法：将参数填充进 SQL 中，以便展示完整的 MySQL SQL
    private String fillParams(String sql, Object... params) {
        if (params == null || params.length == 0) return sql;
        String result = sql;
        for (Object p : params) {
            String valStr = "NULL";
            if (p != null) {
                if (p instanceof String) {
                    valStr = "'" + p.toString().replace("'", "''") + "'";
                } else {
                    valStr = p.toString();
                }
            }
            result = result.replaceFirst("\\?", valStr);
        }
        return result;
    }

    // 辅助方法：记录一次 SQL 审计日志
    private void recordAudit(String mysqlSql, long durationMs, boolean success, String errorMsg, int rowsAffected) {
        String pgSql;
        String trimmed = mysqlSql.trim();
        if (trimmed.startsWith("/* sdtp:direct */")
                || trimmed.startsWith("-- direct")
                || trimmed.startsWith("/* sdt:direct */")) {
            pgSql = mysqlSql.replace("/* sdtp:direct */", "")
                            .replace("-- direct", "")
                            .replace("/* sdt:direct */", "")
                            .trim()
                    + " (SQL Bypass直通模式)";
        } else {
            try {
                pgSql = SqlTranslator.translate(mysqlSql, DialectType.MYSQL, DialectType.POSTGRESQL);
            } catch (Exception e) {
                pgSql = "[本地翻译失败] " + e.getMessage();
            }
        }

        SqlAudit audit = new SqlAudit(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                mysqlSql,
                pgSql,
                durationMs,
                success,
                errorMsg,
                rowsAffected);
        auditQueue.offer(audit);
        while (auditQueue.size() > 100) {
            auditQueue.poll();
        }
    }

    @PostMapping("/init")
    public ResponseEntity<?> initDatabase() {
        log.info("Starting database initialization...");
        long totalStart = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            // 1. DROP 表 (Postgres DDL 加上直通前缀)
            String dropOrders = "/* sdtp:direct */ DROP TABLE IF EXISTS orders";
            long start = System.currentTimeMillis();
            stmt.executeUpdate(dropOrders);
            recordAudit(dropOrders, System.currentTimeMillis() - start, true, null, 0);

            String dropProducts = "/* sdtp:direct */ DROP TABLE IF EXISTS products";
            start = System.currentTimeMillis();
            stmt.executeUpdate(dropProducts);
            recordAudit(dropProducts, System.currentTimeMillis() - start, true, null, 0);

            // 2. CREATE TABLE products (Postgres DDL SERIAL 自增, 加上直通前缀)
            String createProducts = "/* sdtp:direct */ CREATE TABLE products (\n" + "  id SERIAL PRIMARY KEY,\n"
                    + "  name VARCHAR(100) NOT NULL,\n"
                    + "  price DECIMAL(10,2) NOT NULL,\n"
                    + "  stock INT NOT NULL,\n"
                    + "  version INT DEFAULT 0,\n"
                    + "  description TEXT,\n"
                    + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n"
                    + ")";
            start = System.currentTimeMillis();
            stmt.executeUpdate(createProducts);
            recordAudit(createProducts, System.currentTimeMillis() - start, true, null, 0);

            // 3. CREATE TABLE orders (Postgres DDL, 加上直通前缀)
            String createOrders = "/* sdtp:direct */ CREATE TABLE orders (\n" + "  order_id VARCHAR(50) PRIMARY KEY,\n"
                    + "  product_id INT NOT NULL,\n"
                    + "  quantity INT NOT NULL,\n"
                    + "  total_amount DECIMAL(10,2) NOT NULL,\n"
                    + "  buyer_name VARCHAR(100) NOT NULL,\n"
                    + "  status VARCHAR(20) DEFAULT 'PENDING',\n"
                    + "  pay_time TIMESTAMP NULL DEFAULT NULL,\n"
                    + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n"
                    + ")";
            start = System.currentTimeMillis();
            stmt.executeUpdate(createOrders);
            recordAudit(createOrders, System.currentTimeMillis() - start, true, null, 0);

            // 4. INSERT 初始商品数据 (MySQL 语法)
            String insertTpl =
                    "INSERT INTO `products` (`id`, `name`, `price`, `stock`, `version`, `description`) VALUES (?, ?, ?, ?, 0, ?)";

            Object[][] products = {
                {1, "iPhone 15 Pro", 7999.00, 100, "钛金属设计，A17 Pro 芯片"},
                {2, "MacBook Air M3", 8999.00, 50, "超轻薄设计，强劲 M3 芯片"},
                {3, "iPad Pro", 6199.00, 80, "Ultra Retina XDR 屏幕"},
                {4, "Apple Watch S9", 2999.00, 120, "全新手势操作，健康监测"}
            };

            for (Object[] prod : products) {
                String filledSql = fillParams(insertTpl, prod);
                start = System.currentTimeMillis();
                try (PreparedStatement pstmt = conn.prepareStatement(insertTpl)) {
                    pstmt.setInt(1, (Integer) prod[0]);
                    pstmt.setString(2, (String) prod[1]);
                    pstmt.setDouble(3, (Double) prod[2]);
                    pstmt.setInt(4, (Integer) prod[3]);
                    pstmt.setString(5, (String) prod[4]);
                    pstmt.executeUpdate();
                    recordAudit(filledSql, System.currentTimeMillis() - start, true, null, 1);
                } catch (Exception e) {
                    recordAudit(filledSql, System.currentTimeMillis() - start, false, e.getMessage(), 0);
                    throw e;
                }
            }

            // 重置计数器
            totalOrders.set(0);
            totalRevenueCent.set(0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "Database successfully initialized with 4 products.");
            result.put("durationMs", System.currentTimeMillis() - totalStart);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Database initialization failed", e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/order")
    public ResponseEntity<?> placeOrder(@RequestParam(defaultValue = "pessimistic") String lockMode) {
        long totalStart = System.currentTimeMillis();

        // 随机选择商品(1~4)，买家，购买数量(1~3)
        int productId = (int) (Math.random() * 4) + 1;
        String buyer = BUYERS[(int) (Math.random() * BUYERS.length)];
        int quantity = (int) (Math.random() * 3) + 1;
        String orderId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        int maxRetries = "optimistic".equalsIgnoreCase(lockMode) ? 3 : 1;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false); // 开启事务

                try {
                    // 1. 查询商品 info
                    double price = 0.0;
                    int stock = 0;
                    int version = 0;
                    String selectSql;

                    if ("pessimistic".equalsIgnoreCase(lockMode)) {
                        // 悲观行锁 (MySQL FOR UPDATE 语法)
                        selectSql = "SELECT id, name, price, stock, version FROM products WHERE id = ? FOR UPDATE";
                    } else {
                        // 乐观锁，先查询不加锁
                        selectSql = "SELECT id, name, price, stock, version FROM products WHERE id = ?";
                    }

                    String selectFilled = fillParams(selectSql, productId);
                    long start = System.currentTimeMillis();
                    try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                        pstmt.setInt(1, productId);
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (rs.next()) {
                                price = rs.getDouble("price");
                                stock = rs.getInt("stock");
                                version = rs.getInt("version");
                            } else {
                                throw new RuntimeException("Product not found with ID: " + productId);
                            }
                        }
                        recordAudit(selectFilled, System.currentTimeMillis() - start, true, null, 1);
                    } catch (Exception e) {
                        recordAudit(selectFilled, System.currentTimeMillis() - start, false, e.getMessage(), 0);
                        throw e;
                    }

                    // 2. 校验库存
                    if (stock < quantity) {
                        throw new RuntimeException(
                                "库存不足 (Stock Out)! 商品ID: " + productId + ", 剩余库存: " + stock + ", 订购数量: " + quantity);
                    }

                    // 3. 扣减库存
                    String updateSql;
                    String updateFilled;
                    int rowsUpdated = 0;

                    if ("pessimistic".equalsIgnoreCase(lockMode)) {
                        updateSql = "UPDATE products SET stock = stock - ? WHERE id = ?";
                        updateFilled = fillParams(updateSql, quantity, productId);
                        start = System.currentTimeMillis();
                        try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                            pstmt.setInt(1, quantity);
                            pstmt.setInt(2, productId);
                            rowsUpdated = pstmt.executeUpdate();
                            recordAudit(updateFilled, System.currentTimeMillis() - start, true, null, rowsUpdated);
                        } catch (Exception e) {
                            recordAudit(updateFilled, System.currentTimeMillis() - start, false, e.getMessage(), 0);
                            throw e;
                        }
                    } else {
                        // 乐观锁带版本号更新 (MySQL version 校验)
                        updateSql =
                                "UPDATE products SET stock = stock - ?, version = version + 1 WHERE id = ? AND version = ? AND stock >= ?";
                        updateFilled = fillParams(updateSql, quantity, productId, version, quantity);
                        start = System.currentTimeMillis();
                        try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                            pstmt.setInt(1, quantity);
                            pstmt.setInt(2, productId);
                            pstmt.setInt(3, version);
                            pstmt.setInt(4, quantity);
                            rowsUpdated = pstmt.executeUpdate();
                            recordAudit(updateFilled, System.currentTimeMillis() - start, true, null, rowsUpdated);
                        } catch (Exception e) {
                            recordAudit(updateFilled, System.currentTimeMillis() - start, false, e.getMessage(), 0);
                            throw e;
                        }
                    }

                    // 如果是乐观锁且更新行数为 0，说明发生版本冲突，回滚并抛出异常
                    if (rowsUpdated == 0) {
                        throw new ConcurrentModificationException("乐观锁版本冲突，商品已被其他事务更新。");
                    }

                    // 4. 创建订单 (MySQL NOW() 函数，反引号已剥离)
                    double totalAmount = price * quantity;
                    String insertSql =
                            "INSERT INTO orders (order_id, product_id, quantity, total_amount, buyer_name, status, pay_time, created_at)\n"
                                    + "VALUES (?, ?, ?, ?, ?, 'PAID', NOW(), NOW())";
                    String insertFilled = fillParams(insertSql, orderId, productId, quantity, totalAmount, buyer);
                    start = System.currentTimeMillis();
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        pstmt.setString(1, orderId);
                        pstmt.setInt(2, productId);
                        pstmt.setInt(3, quantity);
                        pstmt.setDouble(4, totalAmount);
                        pstmt.setString(5, buyer);
                        pstmt.executeUpdate();
                        recordAudit(insertFilled, System.currentTimeMillis() - start, true, null, 1);
                    } catch (Exception e) {
                        recordAudit(insertFilled, System.currentTimeMillis() - start, false, e.getMessage(), 0);
                        throw e;
                    }

                    conn.commit(); // 提交事务

                    // 累加全局指标
                    totalOrders.incrementAndGet();
                    totalRevenueCent.addAndGet((long) (totalAmount * 100));

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("orderId", orderId);
                    response.put("buyerName", buyer);
                    response.put("quantity", quantity);
                    response.put("totalAmount", totalAmount);
                    response.put("lockModeUsed", lockMode);
                    response.put("attempts", attempt);
                    response.put("durationMs", System.currentTimeMillis() - totalStart);
                    return ResponseEntity.ok(response);

                } catch (ConcurrentModificationException cme) {
                    conn.rollback(); // 发生冲突，事务回滚并重试
                    lastException = cme;
                    if (attempt < maxRetries) {
                        Thread.sleep(50); // 短暂休眠后重试
                    }
                } catch (Exception e) {
                    conn.rollback(); // 其他业务异常直接回滚，不重试
                    throw e;
                }
            } catch (Exception e) {
                lastException = e;
                if (!"乐观锁版本冲突，商品已被其他事务更新。".equals(e.getMessage())) {
                    // 非乐观锁重试冲突，直接跳出不重试
                    break;
                }
            }
        }

        // 重试耗尽，返回错误
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("error", lastException != null ? lastException.getMessage() : "Unknown execution error");
        error.put("durationMs", System.currentTimeMillis() - totalStart);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboardData() {
        try {
            Map<String, Object> result = new LinkedHashMap<>();

            // 查出商品的销量、库存等
            List<Map<String, Object>> productsList = new ArrayList<>();
            // 使用 MySQL 方言中的 IFNULL, LEFT JOIN 和 GROUP BY / Subquery
            String queryProducts = "SELECT p.`id`, p.`name`, p.`price`, p.`stock`, p.`version`, p.`description`,\n"
                    + "       (SELECT IFNULL(SUM(o.`quantity`), 0) FROM `orders` o WHERE o.`product_id` = p.`id`) as `sold_count`\n"
                    + "FROM `products` p ORDER BY p.`id` ASC";

            long start = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(queryProducts);
                    ResultSet rs = pstmt.executeQuery()) {

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("name", rs.getString("name"));
                    row.put("price", rs.getDouble("price"));
                    row.put("stock", rs.getInt("stock"));
                    row.put("version", rs.getInt("version"));
                    row.put("description", rs.getString("description"));
                    row.put("sold_count", rs.getInt("sold_count"));
                    productsList.add(row);
                }
                recordAudit(queryProducts, System.currentTimeMillis() - start, true, null, productsList.size());
            } catch (Exception e) {
                recordAudit(queryProducts, System.currentTimeMillis() - start, false, e.getMessage(), 0);
                throw e;
            }

            // 查出最近 10 条订单流水 (MySQL 方言，包含 IFNULL, JOIN, ORDER BY 和 LIMIT)
            List<Map<String, Object>> ordersList = new ArrayList<>();
            String queryOrders = "SELECT o.`order_id`, o.`product_id`, p.`name` as `product_name`, o.`quantity`,\n"
                    + "       o.`total_amount`, o.`buyer_name`, o.`status`, IFNULL(o.`pay_time`, '1970-01-01 00:00:00') as `pay_time`, o.`created_at`\n"
                    + "FROM `orders` o JOIN `products` p ON o.`product_id` = p.`id` \n"
                    + "ORDER BY o.`created_at` DESC LIMIT 10";

            start = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(queryOrders);
                    ResultSet rs = pstmt.executeQuery()) {

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("order_id", rs.getString("order_id"));
                    row.put("product_id", rs.getInt("product_id"));
                    row.put("product_name", rs.getString("product_name"));
                    row.put("quantity", rs.getInt("quantity"));
                    row.put("total_amount", rs.getDouble("total_amount"));
                    row.put("buyer_name", rs.getString("buyer_name"));
                    row.put("status", rs.getString("status"));
                    row.put("pay_time", rs.getString("pay_time"));
                    row.put("created_at", rs.getString("created_at"));
                    ordersList.add(row);
                }
                recordAudit(queryOrders, System.currentTimeMillis() - start, true, null, ordersList.size());
            } catch (Exception e) {
                recordAudit(queryOrders, System.currentTimeMillis() - start, false, e.getMessage(), 0);
                throw e;
            }

            // 计算全局总计（总库存）
            int totalStock = 0;
            for (Map<String, Object> p : productsList) {
                totalStock += (Integer) p.get("stock");
            }

            result.put("products", productsList);
            result.put("orders", ordersList);
            result.put("totalStock", totalStock);
            result.put("totalOrders", totalOrders.get());
            result.put("totalRevenue", totalRevenueCent.get() / 100.0);

            // 计算平均 SQL 翻译耗时 (从 auditQueue 里获取)
            double avgDuration = 0;
            int count = 0;
            for (SqlAudit audit : auditQueue) {
                avgDuration += audit.getDurationMs();
                count++;
            }
            result.put("avgSqlDurationMs", count > 0 ? Math.round((avgDuration / count) * 10.0) / 10.0 : 0.0);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/audit")
    public ResponseEntity<?> getAuditLogs() {
        // 返回双端队列中存储的记录，把最旧的放在前面，最火的放在最下
        return ResponseEntity.ok(new ArrayList<>(auditQueue));
    }

    @PostMapping("/playground")
    public ResponseEntity<?> executePlaygroundQuery(@RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Parameter 'sql' is required");
        }

        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            boolean hasResultSet = stmt.execute(sql);
            long duration = System.currentTimeMillis() - start;

            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    ResultSetMetaData md = rs.getMetaData();
                    int columnCount = md.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = md.getColumnLabel(i);
                            row.put(columnName, rs.getObject(i));
                        }
                        list.add(row);
                    }
                    recordAudit(sql, duration, true, null, list.size());
                    return ResponseEntity.ok(list);
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                recordAudit(sql, duration, true, null, updateCount);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("updateCount", updateCount);
                result.put("durationMs", duration);
                return ResponseEntity.ok(result);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            recordAudit(sql, duration, false, e.getMessage(), 0);

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("durationMs", duration);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/large-packet")
    public ResponseEntity<?> testLargePacket(@RequestParam(defaultValue = "17") int sizeMb) {
        long totalStart = System.currentTimeMillis();

        if (sizeMb <= 0 || sizeMb > 64) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "Size must be between 1 and 64 MB");
            return ResponseEntity.badRequest().body(err);
        }

        String tableName = "large_packet_test_" + System.currentTimeMillis();
        long generateTimeMs = 0;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long cleanTimeMs = 0;
        boolean dataMatched = false;
        int readLen = -1;

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            // 1. 创建大包临时表 (直通建表以免 Calcite DDL 异常)
            String createTableSql =
                    "/* sdtp:direct */ CREATE TABLE " + tableName + " (id INT PRIMARY KEY, content TEXT)";
            stmt.execute(createTableSql);
            recordAudit(createTableSql, 0, true, null, 0);

            // 2. 内存生成测试字符大文本
            long start = System.currentTimeMillis();
            int totalBytes = sizeMb * 1024 * 1024;
            char[] chars = new char[totalBytes];
            java.util.Arrays.fill(chars, 'X');
            String testContent = new String(chars);
            generateTimeMs = System.currentTimeMillis() - start;

            // 3. 写入测试 (Client -> Proxy -> PGDB)
            String insertSql = "/* sdtp:direct */ INSERT INTO " + tableName + " (id, content) VALUES (?, ?)";
            start = System.currentTimeMillis();
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setInt(1, 1);
                pstmt.setString(2, testContent);
                pstmt.executeUpdate();
            }
            writeTimeMs = System.currentTimeMillis() - start;
            recordAudit(
                    "INSERT INTO " + tableName + " (id, content) VALUES (1, [Large Text: " + sizeMb + "MB])",
                    writeTimeMs,
                    true,
                    null,
                    1);

            // 4. 读取与一致性校验 (PGDB -> Proxy -> Client)
            String selectSql = "/* sdtp:direct */ SELECT content FROM " + tableName + " WHERE id = 1";
            start = System.currentTimeMillis();
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql);
                    ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String fetchedContent = rs.getString("content");
                    readLen = fetchedContent.length();
                    dataMatched = testContent.equals(fetchedContent);
                }
            }
            readTimeMs = System.currentTimeMillis() - start;
            recordAudit("SELECT content FROM " + tableName + " WHERE id = 1", readTimeMs, true, null, 1);

            // 5. 清理临时表
            start = System.currentTimeMillis();
            String dropTableSql = "/* sdtp:direct */ DROP TABLE " + tableName;
            stmt.execute(dropTableSql);
            cleanTimeMs = System.currentTimeMillis() - start;
            recordAudit(dropTableSql, cleanTimeMs, true, null, 0);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("packetSizeMb", sizeMb);
            response.put("generateTimeMs", generateTimeMs);
            response.put("writeTimeMs", writeTimeMs);
            response.put("readTimeMs", readTimeMs);
            response.put("cleanTimeMs", cleanTimeMs);
            response.put("totalTimeMs", System.currentTimeMillis() - totalStart);
            response.put("dataMatched", dataMatched);
            response.put("readLength", readLen);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Large packet E2E test failed", e);
            // 兜底清理
            try (Connection conn = dataSource.getConnection();
                    Statement stmt = conn.createStatement()) {
                stmt.execute("/* sdtp:direct */ DROP TABLE IF EXISTS " + tableName);
            } catch (Exception ignored) {
            }

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            error.put("durationMs", System.currentTimeMillis() - totalStart);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // 自定义并发更新冲突异常
    public static class ConcurrentModificationException extends RuntimeException {
        public ConcurrentModificationException(String message) {
            super(message);
        }
    }
}
