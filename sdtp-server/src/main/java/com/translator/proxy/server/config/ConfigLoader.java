package com.translator.proxy.server.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * YAML 配置文件加载器。
 *
 * <p>支持新旧两种配置格式：
 * <ul>
 *   <li>新格式：backends 列表（每个后端含 name、dialect、jdbc-url、账密、连接池参数）</li>
 *   <li>旧格式：单 target（自动转换为 backends 列表）</li>
 * </ul>
 *
 * <p>查找顺序：
 * <ol>
 *   <li>系统属性 -Dproxy.config=/path/to/config.yml</li>
 *   <li>classpath 下的 proxy-config.yml</li>
 *   <li>当前目录下的 proxy-config.yml</li>
 * </ol>
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {}

    /**
     * 解析配置文件的真实路径（与 load() 同一查找顺序）。
     *
     * @return 配置文件路径，若最终使用 classpath 或默认值则返回 null
     */
    public static String resolveConfigPath() {
        String configPath = System.getProperty("proxy.config");
        if (configPath != null) {
            return configPath;
        }
        // classpath 资源没有文件路径
        InputStream classpathStream = ConfigLoader.class.getClassLoader().getResourceAsStream("proxy-config.yml");
        if (classpathStream != null) {
            try {
                classpathStream.close();
            } catch (IOException ignored) {
            }
            return null; // classpath 资源不可文件监听
        }
        // 当前目录下的文件
        File file = new File("proxy-config.yml");
        if (file.exists()) {
            return file.getAbsolutePath();
        }
        return null;
    }

    public static ProxyConfig load() {
        String configPath = System.getProperty("proxy.config");
        if (configPath != null) {
            return loadFromFile(configPath);
        }

        InputStream classpathStream = ConfigLoader.class.getClassLoader().getResourceAsStream("proxy-config.yml");
        if (classpathStream != null) {
            log.info("Loading config from classpath:proxy-config.yml");
            return loadFromStream(classpathStream);
        }

        try {
            return loadFromFile("proxy-config.yml");
        } catch (Exception e) {
            log.info("No proxy-config.yml found, using defaults");
            return new ProxyConfig();
        }
    }

    /**
     * 从文件加载配置，失败时返回 null 而不抛异常（供 watcher 热加载使用）。
     *
     * @param path 配置文件路径
     * @return 解析后的 ProxyConfig，失败时返回 null
     */
    public static ProxyConfig loadFromFileOrNull(String path) {
        if (path == null) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(path)) {
            return loadFromStream(fis);
        } catch (Exception e) {
            log.error("Failed to reload config from {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static ProxyConfig loadFromFile(String path) {
        log.info("Loading config from file: {}", path);
        try (FileInputStream fis = new FileInputStream(path)) {
            return loadFromStream(fis);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Config file not found: " + path, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config from: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ProxyConfig loadFromStream(InputStream stream) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(stream);

        ProxyConfig config = new ProxyConfig();

        // === proxy 段 ===
        Map<String, Object> proxy = (Map<String, Object>) root.get("proxy");
        if (proxy != null) {
            if (proxy.get("port") != null) {
                config.setPort(((Number) proxy.get("port")).intValue());
            }
            if (proxy.get("max-allowed-packet") != null) {
                config.setMaxAllowedPacket(((Number) proxy.get("max-allowed-packet")).intValue());
            }
            if (proxy.get("frontend-protocol") != null) {
                config.setFrontendProtocol((String) proxy.get("frontend-protocol"));
            }
            Map<String, Object> authMap = (Map<String, Object>) proxy.get("auth");
            if (authMap != null) {
                ProxyConfig.AuthConfig auth = config.getAuth();
                if (authMap.get("user") != null) {
                    auth.setUser((String) authMap.get("user"));
                }
                if (authMap.get("password") != null) {
                    auth.setPassword((String) authMap.get("password"));
                }
            }
        }

        // === backends 列表（新格式） ===
        List<Map<String, Object>> backendsList = (List<Map<String, Object>>) root.get("backends");
        if (backendsList != null && !backendsList.isEmpty()) {
            for (Map<String, Object> bm : backendsList) {
                ProxyConfig.TargetConfig tc = parseTargetConfig(bm);
                config.getBackends().add(tc);
            }
            log.info("Loaded {} backends from config", config.getBackends().size());
        }

        // === target 段（旧格式，向后兼容） ===
        if (config.getBackends().isEmpty()) {
            Map<String, Object> target = (Map<String, Object>) root.get("target");
            if (target != null) {
                ProxyConfig.TargetConfig tc = parseTargetConfig(target);
                if (tc.getName() == null) {
                    // 旧格式无 name，用 jdbc-url 中的数据库名或默认值
                    String url = tc.getJdbcUrl();
                    if (url != null && url.contains("/")) {
                        String dbName = url.substring(url.lastIndexOf('/') + 1);
                        int paramIdx = dbName.indexOf('?');
                        if (paramIdx > 0) dbName = dbName.substring(0, paramIdx);
                        tc.setName(dbName);
                    } else {
                        tc.setName("mydb");
                    }
                }
                config.getBackends().add(tc);
                log.info("Loaded single target (backward compat) as backend '{}'", tc.getName());
            }
        }

        // === translation 段 ===
        Map<String, Object> translation = (Map<String, Object>) root.get("translation");
        if (translation != null) {
            ProxyConfig.TranslationConf trc = config.getTranslation();
            if (translation.get("keyword-case") != null) {
                trc.setKeywordCase((String) translation.get("keyword-case"));
            }
            if (translation.get("identifier-case") != null) {
                trc.setIdentifierCase((String) translation.get("identifier-case"));
            }
        }

        // === metrics 段 ===
        Map<String, Object> metrics = (Map<String, Object>) root.get("metrics");
        if (metrics != null) {
            ProxyConfig.MetricsConf mc = config.getMetrics();
            if (metrics.get("enabled") != null) {
                mc.setEnabled((Boolean) metrics.get("enabled"));
            }
            if (metrics.get("port") != null) {
                mc.setPort(((Number) metrics.get("port")).intValue());
            }
        }

        // === reload 段（热更新配置） ===
        Map<String, Object> reloadMap = (Map<String, Object>) root.get("reload");
        if (reloadMap != null) {
            if (reloadMap.get("queue-size") != null) {
                config.setReloadQueueCapacity(((Number) reloadMap.get("queue-size")).intValue());
            }
            if (reloadMap.get("drain-timeout-ms") != null) {
                config.setReloadDrainTimeoutMs(((Number) reloadMap.get("drain-timeout-ms")).intValue());
            }
            if (reloadMap.get("debounce-ms") != null) {
                config.setReloadDebounceMs(((Number) reloadMap.get("debounce-ms")).intValue());
            }
        }

        return config;
    }

    private static ProxyConfig.TargetConfig parseTargetConfig(Map<String, Object> map) {
        ProxyConfig.TargetConfig tc = new ProxyConfig.TargetConfig();
        if (map.get("name") != null) tc.setName((String) map.get("name"));
        if (map.get("dialect") != null) tc.setDialect((String) map.get("dialect"));
        if (map.get("jdbc-url") != null) tc.setJdbcUrl((String) map.get("jdbc-url"));
        if (map.get("username") != null) tc.setUsername((String) map.get("username"));
        if (map.get("password") != null) tc.setPassword((String) map.get("password"));
        if (map.get("max-pool-size") != null) tc.setMaxPoolSize(((Number) map.get("max-pool-size")).intValue());
        if (map.get("min-idle") != null) tc.setMinIdle(((Number) map.get("min-idle")).intValue());
        return tc;
    }
}
