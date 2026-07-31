package com.translator.proxy.backend.metadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.core.metadata.ColumnMetadata;
import com.translator.core.metadata.MetadataProvider;
import com.translator.core.metadata.TableMetadata;

/**
 * 基于 {@link DataSource} 连接池的代理端元数据提供者。
 *
 * <p>
 * 仿照 {@code JdbcMetadataProvider} 实现，但每次提取元数据时均通过 {@link DataSource#getConnection()} 动态获取并立即释放物理连接，
 * 避免长连接失效或占用连接池，适合代理服务器环境使用。
 */
public class BackendMetadataProvider implements MetadataProvider {
    private static final Logger log = LoggerFactory.getLogger(BackendMetadataProvider.class);

    private final DataSource dataSource;
    private final int maxTables;
    private final Map<String, TableMetadata> cache = new ConcurrentHashMap<>();

    // 所有已知的表名集合，支持大小写自适应
    private final Set<String> allTableNames = new CopyOnWriteArraySet<>();
    private volatile boolean initialized = false;

    public BackendMetadataProvider(DataSource dataSource) {
        this(dataSource, 100); // 默认限制 100 张表
    }

    public BackendMetadataProvider(DataSource dataSource, int maxTables) {
        this.dataSource = dataSource;
        this.maxTables = maxTables;
    }

    @Override
    public TableMetadata getTable(String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            return null;
        }

        // 快速判断表名是否是数据库中真实存在的表（排除大小写不匹配干扰）
        if (!getTableNames().contains(tableName)) {
            return null;
        }

        // 检查缓存中是否存在
        TableMetadata cached = cache.get(tableName);
        if (cached != null) {
            return cached;
        }

        // maxTables 保护开关，防止恶意的多表/巨表 SQL 拖垮元数据拉取
        if (cache.size() >= maxTables && !cache.containsKey(tableName)) {
            log.warn("元数据加载表数量已达上限 [{}], 拒绝加载表 [{}] 的列结构", maxTables, tableName);
            return null;
        }

        TableMetadata meta = loadTableMetadata(tableName);
        if (meta != null) {
            cache.put(tableName, meta);
            // 同时也把实际加载成功的名字也放缓存，以便之后快速命中
            if (!tableName.equals(meta.getTableName())) {
                cache.put(meta.getTableName(), meta);
            }
            return meta;
        }
        return null;
    }

    @Override
    public Set<String> getTableNames() {
        if (!initialized) {
            initializeTableNames();
        }
        return allTableNames;
    }

    private void initializeTableNames() {
        synchronized (this) {
            if (initialized) {
                return;
            }
            if (dataSource == null) {
                log.warn("DataSource 不可用，无法初始化表名列表");
                initialized = true;
                return;
            }

            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                String catalog = null;
                try {
                    catalog = connection.getCatalog();
                }
                catch (Exception e) {
                    // 忽略异常
                }
                String schema = null;
                try {
                    schema = connection.getSchema();
                }
                catch (Exception e) {
                    // 忽略异常
                }

                // 获取当前 catalog/schema 下的所有表和视图，快速加载表名
                try (ResultSet rs = metaData.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        if (tableName != null) {
                            allTableNames.add(tableName);
                            // 同时加入大写和小写，以在 contains 匹配时完全自适应各种大小写查找
                            allTableNames.add(tableName.toUpperCase());
                            allTableNames.add(tableName.toLowerCase());
                        }
                    }
                }
                log.debug("成功初始化所有表名列表，共加载 [{}] 个候选名字", allTableNames.size());
            }
            catch (SQLException e) {
                log.error("初始化所有表名列表失败", e);
            }
            finally {
                initialized = true;
            }
        }
    }

    private TableMetadata loadTableMetadata(String tableName) {
        if (dataSource == null) {
            log.warn("DataSource 不可用，无法加载表 [{}] 的元数据", tableName);
            return null;
        }

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = null;
            try {
                catalog = connection.getCatalog();
            }
            catch (Exception e) {
                // 忽略异常
            }
            String schema = null;
            try {
                schema = connection.getSchema();
            }
            catch (Exception e) {
                // 忽略异常
            }

            // 1. 原样查
            List<ColumnMetadata> columns = fetchColumns(metaData, catalog, schema, tableName);
            if (!columns.isEmpty()) {
                log.debug("成功原样加载表 [{}] 的列元数据", tableName);
                return new TableMetadata(tableName, columns);
            }

            // 2. 转大写查
            String upperName = tableName.toUpperCase();
            if (!upperName.equals(tableName)) {
                columns = fetchColumns(metaData, catalog, schema, upperName);
                if (!columns.isEmpty()) {
                    log.debug("大小写自适应：成功以大写 [{}] 加载表列元数据", upperName);
                    return new TableMetadata(upperName, columns);
                }
            }

            // 3. 转小写查
            String lowerName = tableName.toLowerCase();
            if (!lowerName.equals(tableName)) {
                columns = fetchColumns(metaData, catalog, schema, lowerName);
                if (!columns.isEmpty()) {
                    log.debug("大小写自适应：成功以小写 [{}] 加载表列元数据", lowerName);
                    return new TableMetadata(lowerName, columns);
                }
            }

            log.warn("表 [{}] 列结构加载失败", tableName);
            return null;
        }
        catch (SQLException e) {
            log.error("加载表 [{}] 的元数据失败", tableName, e);
            return null;
        }
    }

    private List<ColumnMetadata> fetchColumns(
            DatabaseMetaData metaData, String catalog, String schema, String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(catalog, schema, tableName, "%")) {
            readColumnsFromResultSet(rs, columns);
        }

        if (columns.isEmpty()) {
            try (ResultSet rs = metaData.getColumns(null, null, tableName, "%")) {
                readColumnsFromResultSet(rs, columns);
            }
        }
        return columns;
    }

    private void readColumnsFromResultSet(ResultSet rs, List<ColumnMetadata> columns) throws SQLException {
        while (rs.next()) {
            String columnName = rs.getString("COLUMN_NAME");
            int dataType = rs.getInt("DATA_TYPE");
            int columnSize = rs.getInt("COLUMN_SIZE");
            int decimalDigits = rs.getInt("DECIMAL_DIGITS");
            boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
            columns.add(new ColumnMetadata(columnName, dataType, columnSize, decimalDigits, nullable));
        }
    }
}
