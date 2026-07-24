package com.translator.demo.proxy.controller;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryController {

    @Autowired
    private DataSource dataSource;

    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        String db = request.get("db");
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Parameter 'sql' is required");
        }

        try (Connection conn = dataSource.getConnection()) {
            if (db != null && !db.trim().isEmpty()) {
                conn.setCatalog(db);
            }
            try (Statement stmt = conn.createStatement()) {
                boolean hasResultSet = stmt.execute(sql);
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
                        return ResponseEntity.ok(list);
                    }
                } else {
                    int updateCount = stmt.getUpdateCount();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("updateCount", updateCount);
                    return ResponseEntity.ok(result);
                }
            }
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/info")
    public ResponseEntity<?> getInfo() {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            List<String> databases = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            }
            String currentDb = conn.getCatalog();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("databases", databases);
            result.put("currentDb", currentDb);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
