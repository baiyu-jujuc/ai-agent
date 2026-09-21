package com.baiyu.agent.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关配置。每一层能力一个开关，目的很具体：
 * 出问题时能立刻判断"是哪一层引起的"，而不是把所有开关一起关掉再猜。
 */
@ConfigurationProperties(prefix = "agent.gateway")
public class GatewayProperties {

    /** 总开关：false 时网关内部直接调用模型，不做计量/缓存/限流。 */
    private boolean enabled = true;

    /** L1：是否写用量记录。 */
    private boolean meteringEnabled = true;

    /** L2：是否启用限流/熔断/重试。默认关闭——先上计量，再开稳定性。 */
    private boolean resilienceEnabled = false;

    /** L4：是否上报 Micrometer 指标。 */
    private boolean metricsEnabled = true;

    /** L3：Prompt 模板是否走数据库版本管理。 */
    private boolean promptStoreEnabled = true;

    /** 网关层超时，必须小于 AiConfig 里 HTTP 层的 60s，否则轮不到它生效。 */
    private int timeoutSeconds = 30;

    /** 每个模型每秒放行的请求数（resilience 开启时生效）。 */
    private int rateLimitPerModelPerSecond = 5;

    /** 失败请求的最大重试次数（resilience 开启时生效）。 */
    private int retryMaxAttempts = 2;

    private Cache cache = new Cache();

    private Map<String, Route> routes = new LinkedHashMap<>();

    private Pricing pricing = new Pricing();

    public static class Cache {

        private boolean enabled = true;
        private double similarityThreshold = 0.92;
        private int ttlHours = 24;
        private String collection = "semantic_cache";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public int getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(int ttlHours) {
            this.ttlHours = ttlHours;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }
    }

    /** 一条路由：主模型 + 逗号分隔的降级链。 */
    public static class Route {

        private String primary;
        private String fallbacks = "";

        public String getPrimary() {
            return primary;
        }

        public void setPrimary(String primary) {
            this.primary = primary;
        }

        public String getFallbacks() {
            return fallbacks;
        }

        public void setFallbacks(String fallbacks) {
            this.fallbacks = fallbacks;
        }
    }

    public static class Pricing {

        /** key = 模型名，value = 单价（单位：微元 / 百万 token）。 */
        private Map<String, Price> models = new LinkedHashMap<>();

        public Map<String, Price> getModels() {
            return models;
        }

        public void setModels(Map<String, Price> models) {
            this.models = models;
        }
    }

    public static class Price {

        private long promptPerMillion;
        private long completionPerMillion;

        public long getPromptPerMillion() {
            return promptPerMillion;
        }

        public void setPromptPerMillion(long promptPerMillion) {
            this.promptPerMillion = promptPerMillion;
        }

        public long getCompletionPerMillion() {
            return completionPerMillion;
        }

        public void setCompletionPerMillion(long completionPerMillion) {
            this.completionPerMillion = completionPerMillion;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMeteringEnabled() {
        return meteringEnabled;
    }

    public void setMeteringEnabled(boolean meteringEnabled) {
        this.meteringEnabled = meteringEnabled;
    }

    public boolean isResilienceEnabled() {
        return resilienceEnabled;
    }

    public void setResilienceEnabled(boolean resilienceEnabled) {
        this.resilienceEnabled = resilienceEnabled;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public boolean isPromptStoreEnabled() {
        return promptStoreEnabled;
    }

    public void setPromptStoreEnabled(boolean promptStoreEnabled) {
        this.promptStoreEnabled = promptStoreEnabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getRateLimitPerModelPerSecond() {
        return rateLimitPerModelPerSecond;
    }

    public void setRateLimitPerModelPerSecond(int rateLimitPerModelPerSecond) {
        this.rateLimitPerModelPerSecond = rateLimitPerModelPerSecond;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Map<String, Route> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Route> routes) {
        this.routes = routes;
    }

    public Pricing getPricing() {
        return pricing;
    }

    public void setPricing(Pricing pricing) {
        this.pricing = pricing;
    }
}
