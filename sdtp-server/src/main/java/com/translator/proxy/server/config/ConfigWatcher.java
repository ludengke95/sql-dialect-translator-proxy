package com.translator.proxy.server.config;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.backend.BackendEntry;
import com.translator.proxy.backend.BackendPoolManager;
import com.translator.proxy.metrics.ReloadMetrics;

/**
 * 配置文件监听器 —— 监听 YAML 配置文件变化并热更新 backends。
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>使用 {@link WatchService} 监听配置文件所在目录的修改事件。</li>
 *   <li>收到事件后防抖等待（{@code reloadDebounceMs}），期间新事件重置计时器。</li>
 *   <li>重新加载配置，与当前配置对比 backends 列表（按 name 匹配）。</li>
 *   <li>分派三类操作到 {@link BackendPoolManager}：
 *    新增、删除、变更（同名但配置不同）。</li>
 *   <li>port / auth / 全局 translation 变化 → 记录 WARN 提示重启，不做热更新。</li>
 * </ol>
 *
 * <p>作为 daemon 线程运行，可通过 {@link #stop()} 优雅停止。
 */
public class ConfigWatcher implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);
    private final String configFilePath;
    private final int debounceMs;
    private final BackendPoolManager poolManager;
    private ProxyConfig currentConfig;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private WatchService watchService;
    private Thread watcherThread;
    /**
     * 创建配置文件监听器。
     *
     * @param configFilePath 配置文件的绝对路径
     * @param debounceMs     防抖间隔（毫秒）
     * @param poolManager    后端连接池管理器
     * @param currentConfig  当前生效的配置（用于差异对比）
     */
    public ConfigWatcher(
            String configFilePath, int debounceMs, BackendPoolManager poolManager, ProxyConfig currentConfig) {
        this.configFilePath = configFilePath;
        this.debounceMs = debounceMs;
        this.poolManager = poolManager;
        this.currentConfig = currentConfig;
    }
    /**
     * 启动 watcher 线程（daemon）。
     */
    public void start() {
        if (configFilePath == null) {
            log.info("No config file path to watch (using classpath or defaults), watcher disabled");
            return;
        }
        File configFile = new File(configFilePath);
        if (!configFile.exists()) {
            log.warn("Config file {} does not exist, watcher disabled", configFilePath);
            return;
        }
        watcherThread = new Thread(this, "config-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        log.info("ConfigWatcher started, watching: {}", configFilePath);
    }
    /**
     * 停止 watcher。
     */
    public void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception e) {
                log.debug("Error closing WatchService: {}", e.getMessage());
            }
        }
        if (watcherThread != null) {
            try {
                watcherThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("ConfigWatcher stopped");
    }

    @Override
    public void run() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            File configFile = new File(configFilePath);
            Path configDir = configFile.getAbsoluteFile().getParentFile().toPath();
            String configFileName = configFile.getName();
            configDir.register(
                    watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            log.info("Watching directory: {} for file: {}", configDir, configFileName);
            while (running.get()) {
                WatchKey key;
                try {
                    key = watchService.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) {
                    continue;
                }
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Path changed = (Path) event.context();
                    if (changed != null
                            && configFileName.equals(changed.getFileName().toString())) {
                        relevant = true;
                        log.debug("Detected change: {} on {}", kind.name(), changed);
                    }
                }
                key.reset();
                if (!relevant) {
                    continue;
                }
                // 防抖：等待 debounceMs，期间新事件重置计时器
                debounceWait();
                if (!running.get()) {
                    break;
                }
                // 重新加载配置
                reloadIfChanged();
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("ConfigWatcher error, stopping", e);
            }
        }
    }
    // ==================== 内部逻辑 ====================
    /**
     * 防抖等待：持续等待直到 debounceMs 内无新事件。
     */
    private void debounceWait() {
        long deadline = System.currentTimeMillis() + debounceMs;
        while (System.currentTimeMillis() < deadline && running.get()) {
            try {
                // 短暂休眠
                Thread.sleep(Math.min(100, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    /**
     * 重新加载配置并与当前配置对比，分派变更。
     */
    private void reloadIfChanged() {
        long startNanos = System.nanoTime();
        log.info("Reloading config from: {}", configFilePath);
        ProxyConfig newConfig = ConfigLoader.loadFromFileOrNull(configFilePath);
        if (newConfig == null) {
            log.error("Failed to load config, keeping current configuration");
            ReloadMetrics.recordReloadFailure();
            return;
        }
        ProxyConfig oldConfig = currentConfig;
        // === 检查不可热更的配置项 ===
        checkNonReloadableChanges(oldConfig, newConfig);
        // === 对比 backends 列表（按 name 匹配） ===
        Map<String, ProxyConfig.TargetConfig> oldBackends = indexByName(oldConfig.getBackends());
        Map<String, ProxyConfig.TargetConfig> newBackends = indexByName(newConfig.getBackends());
        int totalChanges = 0;
        // 1. 新增：new 有、old 无
        for (Map.Entry<String, ProxyConfig.TargetConfig> entry : newBackends.entrySet()) {
            String name = entry.getKey();
            if (!oldBackends.containsKey(name)) {
                log.info("New backend detected: '{}'", name);
                BackendEntry be = toBackendEntry(entry.getValue());
                if (poolManager.addBackend(be)) {
                    totalChanges++;
                    ReloadMetrics.recordReload("add", name);
                }
            }
        }
        // 2. 删除：old 有、new 无
        for (String name : oldBackends.keySet()) {
            if (!newBackends.containsKey(name)) {
                log.info("Backend removed: '{}'", name);
                if (poolManager.removeBackend(name)) {
                    totalChanges++;
                    ReloadMetrics.recordReload("remove", name);
                }
            }
        }
        // 3. 变更：同名但配置不同
        for (Map.Entry<String, ProxyConfig.TargetConfig> entry : newBackends.entrySet()) {
            String name = entry.getKey();
            ProxyConfig.TargetConfig newTc = entry.getValue();
            ProxyConfig.TargetConfig oldTc = oldBackends.get(name);
            if (oldTc != null && !newTc.equals(oldTc)) {
                log.info(
                        "Backend changed: '{}' (jdbcUrl={} → {}, pool={} → {})",
                        name,
                        oldTc.getJdbcUrl(),
                        newTc.getJdbcUrl(),
                        oldTc.getMaxPoolSize(),
                        newTc.getMaxPoolSize());
                BackendEntry be = toBackendEntry(newTc);
                if (poolManager.reloadBackend(be)) {
                    totalChanges++;
                    ReloadMetrics.recordReload("reload", name);
                }
            }
        }
        if (totalChanges > 0) {
            log.info("Config reload complete: {} backends updated", totalChanges);
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            for (String name : newBackends.keySet()) {
                if (oldBackends.containsKey(name)) {
                    if (!oldBackends.get(name).equals(newBackends.get(name))) {
                        ReloadMetrics.observeDuration(name, "reload", seconds / Math.max(totalChanges, 1));
                    }
                } else {
                    ReloadMetrics.observeDuration(name, "add", seconds);
                }
            }
            // 被删除的后端
            Set<String> removed = new HashSet<>(oldBackends.keySet());
            removed.removeAll(newBackends.keySet());
            for (String name : removed) {
                ReloadMetrics.observeDuration(name, "remove", seconds);
            }
            currentConfig = newConfig;
        } else {
            log.info("Config reload: no backend changes detected");
            currentConfig = newConfig;
        }
    }
    /**
     * 检查不可热更新的配置项变更，记录 WARN。
     */
    private void checkNonReloadableChanges(ProxyConfig oldConfig, ProxyConfig newConfig) {
        if (oldConfig.getPort() != newConfig.getPort()) {
            log.warn(
                    "Port changed from {} to {} (requires restart to take effect)",
                    oldConfig.getPort(),
                    newConfig.getPort());
        }
        if (!Objects.equals(oldConfig.getAuth().getUser(), newConfig.getAuth().getUser())) {
            log.warn(
                    "Auth user changed from '{}' to '{}' (requires restart to take effect)",
                    oldConfig.getAuth().getUser(),
                    newConfig.getAuth().getUser());
        }
        if (!Objects.equals(
                oldConfig.getAuth().getPassword(), newConfig.getAuth().getPassword())) {
            log.warn("Auth password changed (requires restart to take effect)");
        }
        if (!oldConfig.getTranslation().equals(newConfig.getTranslation())) {
            log.warn("Global translation config changed (requires restart to take effect)");
        }
    }
    /**
     * 将后端列表按 name 索引为 Map。
     */
    private static Map<String, ProxyConfig.TargetConfig> indexByName(List<ProxyConfig.TargetConfig> backends) {
        Map<String, ProxyConfig.TargetConfig> map = new LinkedHashMap<>();
        for (ProxyConfig.TargetConfig tc : backends) {
            String name = tc.getName();
            if (name != null && !name.isEmpty()) {
                map.put(name, tc);
            }
        }
        return map;
    }
    /**
     * ProxyConfig.TargetConfig → BackendEntry。
     */
    public static BackendEntry toBackendEntry(ProxyConfig.TargetConfig tc) {
        BackendEntry be = new BackendEntry(
                tc.getName(),
                tc.getDialect(),
                tc.getJdbcUrl(),
                tc.getUsername(),
                tc.getPassword(),
                tc.getMaxPoolSize(),
                tc.getMinIdle());
        if (tc.getTranslation() != null) {
            be.setKeywordCase(tc.getTranslation().getKeywordCase());
            be.setIdentifierCase(tc.getTranslation().getIdentifierCase());
        }
        return be;
    }
}
