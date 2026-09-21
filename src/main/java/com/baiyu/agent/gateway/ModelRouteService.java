package com.baiyu.agent.gateway;

import com.baiyu.agent.config.ModelRegistry;
import com.baiyu.agent.gateway.entity.ModelRouteConfig;
import com.baiyu.agent.gateway.repository.ModelRouteConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由解析：数据库表 → application.yml → 代码内置默认值，三级回退。
 *
 * <p>为什么要有本地缓存：这个方法是<b>每次模型调用</b>都会走的路径，
 * 每次都查库等于给主链路加一次数据库往返。30 秒的缓存 + 手动 reload，
 * 是"配置改动能生效"和"主链路不受影响"之间的折中。
 */
@Service
public class ModelRouteService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouteService.class);
    private static final long CACHE_TTL_MILLIS = 30_000L;

    private final ModelRouteConfigRepository repository;
    private final GatewayProperties properties;
    private final ModelRegistry modelRegistry;

    private volatile Map<String, RouteDefinition> cached = Map.of();
    private volatile long loadedAt = 0L;

    public ModelRouteService(ModelRouteConfigRepository repository,
                             GatewayProperties properties,
                             ModelRegistry modelRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.modelRegistry = modelRegistry;
    }

    /** 主模型。请求里显式指定的模型优先于路由配置。 */
    public String primaryOf(String routeKey) {
        return resolve(routeKey).primary();
    }

    public List<String> fallbackChain(String routeKey) {
        return resolve(routeKey).fallbacks();
    }

    public List<String> candidates(String routeKey, String modelOverride) {
        List<String> candidates = new ArrayList<>();
        String primary = (modelOverride == null || modelOverride.isBlank())
                ? resolve(routeKey).primary()
                : modelOverride;
        if (primary != null && !primary.isBlank()) {
            candidates.add(primary);
        }
        for (String fallback : resolve(routeKey).fallbacks()) {
            if (!candidates.contains(fallback)) {
                candidates.add(fallback);
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(modelRegistry.getDefaultModel());
        }
        return candidates;
    }

    /** 当前生效的全部路由（供 GET /api/admin/models 展示）。 */
    public List<RouteView> listRoutes() {
        Map<String, RouteDefinition> routes = loadIfStale();
        if (routes.isEmpty()) {
            routes = builtInDefaults();
            return routes.values().stream().map(r -> RouteView.from(r, "built-in")).toList();
        }
        List<RouteView> views = new ArrayList<>();
        for (Map.Entry<String, RouteDefinition> entry : routes.entrySet()) {
            String source = fromDatabase(entry.getKey()) ? "database" : "application.yml";
            views.add(RouteView.from(entry.getValue(), source));
        }
        return views;
    }

    /** 运行时改路由。改完立刻清缓存，下一次调用就生效（不需要重启）。 */
    @Transactional
    public RouteView update(String routeKey, String primary, String fallbacks) {
        if (routeKey == null || routeKey.isBlank()) {
            throw new IllegalArgumentException("routeKey 不能为空");
        }
        if (primary == null || primary.isBlank()) {
            throw new IllegalArgumentException("primary 不能为空");
        }
        ModelRouteConfig config = repository.findByRouteKey(routeKey)
                .orElseGet(() -> new ModelRouteConfig(routeKey, primary, fallbacks));
        config.setPrimaryModel(primary.trim());
        config.setFallbackModels(fallbacks == null ? "" : fallbacks.trim());
        config.setUpdatedAt(Instant.now());
        config.setEnabled(true);
        repository.save(config);
        reload();
        log.info("路由已更新：{} → primary={}, fallbacks={}", routeKey, primary, fallbacks);
        return RouteView.from(resolve(routeKey), "database");
    }

    /** 强制刷新本地缓存。 */
    public void reload() {
        loadedAt = 0L;
        Map<String, RouteDefinition> fresh = loadFromDatabase();
        cached = mergeWithDefaults(fresh);
        loadedAt = System.currentTimeMillis();
        log.info("路由缓存已刷新，共 {} 条：{}", cached.size(), cached.keySet());
    }

    RouteDefinition resolve(String routeKey) {
        Map<String, RouteDefinition> routes = loadIfStale();
        RouteDefinition definition = routes.get(routeKey);
        if (definition != null) {
            return definition;
        }
        return builtInDefaults().getOrDefault(routeKey,
                new RouteDefinition(routeKey, modelRegistry.getDefaultModel(),
                        List.of(modelRegistry.getFastModel()), true));
    }

    private Map<String, RouteDefinition> loadIfStale() {
        if (System.currentTimeMillis() - loadedAt > CACHE_TTL_MILLIS || loadedAt == 0L) {
            synchronized (this) {
                if (System.currentTimeMillis() - loadedAt > CACHE_TTL_MILLIS || loadedAt == 0L) {
                    reload();
                }
            }
        }
        return cached;
    }

    private Map<String, RouteDefinition> mergeWithDefaults(Map<String, RouteDefinition> fromDb) {
        Map<String, RouteDefinition> merged = new LinkedHashMap<>(builtInDefaults());
        merged.putAll(fromDb);
        return merged;
    }

    /** application.yml 里配的路由，没有就退回 ModelRegistry 里的模型名。 */
    private Map<String, RouteDefinition> builtInDefaults() {
        Map<String, RouteDefinition> defaults = new LinkedHashMap<>();
        for (Map.Entry<String, GatewayProperties.Route> entry : properties.getRoutes().entrySet()) {
            GatewayProperties.Route route = entry.getValue();
            defaults.put(entry.getKey(), new RouteDefinition(entry.getKey(), route.getPrimary(),
                    splitCsv(route.getFallbacks()), true));
        }
        defaults.putIfAbsent("kb_qa", new RouteDefinition("kb_qa", modelRegistry.getProModel(),
                List.of(modelRegistry.getFastModel()), true));
        defaults.putIfAbsent("default_chat", new RouteDefinition("default_chat", modelRegistry.getDefaultModel(),
                List.of(), true));
        return defaults;
    }

    private Map<String, RouteDefinition> loadFromDatabase() {
        Map<String, RouteDefinition> result = new LinkedHashMap<>();
        try {
            for (ModelRouteConfig config : repository.findAll()) {
                if (!config.isEnabled()) {
                    continue;
                }
                result.put(config.getRouteKey(), new RouteDefinition(config.getRouteKey(),
                        config.getPrimaryModel(), splitCsv(config.getFallbackModels()), true));
            }
        } catch (Exception e) {
            // 表还没建好、数据库暂时不可用都不该让模型调用挂掉：退回 yml/内置默认值
            log.warn("读取路由配置失败，改用 application.yml 默认值：{}", e.toString());
        }
        return result;
    }

    private boolean fromDatabase(String routeKey) {
        try {
            return repository.findByRouteKey(routeKey).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public record RouteDefinition(String routeKey, String primary, List<String> fallbacks, boolean enabled) {
    }

    public record RouteView(String routeKey, String primary, List<String> fallbacks, boolean enabled, String source) {

        static RouteView from(RouteDefinition definition, String source) {
            return new RouteView(definition.routeKey(), definition.primary(),
                    definition.fallbacks(), definition.enabled(), source);
        }
    }
}
