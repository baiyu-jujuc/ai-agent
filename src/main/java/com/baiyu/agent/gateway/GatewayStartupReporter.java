package com.baiyu.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把"四层能力各自开没开、为什么没开"打成一行日志。
 *
 * <p>这个类很小，但很实用：排查"缓存怎么不生效"时，
 * 不用去猜是配置没开、还是 EmbeddingModel 不存在、还是阈值不合适——
 * 启动日志里直接写明原因。
 */
@Component
public class GatewayStartupReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayStartupReporter.class);

    private final GatewayProperties properties;
    private final ObjectProvider<SemanticCache> cacheProvider;
    private final String vectorStoreType;

    public GatewayStartupReporter(GatewayProperties properties,
                                  ObjectProvider<SemanticCache> cacheProvider,
                                  @Value("${agent.storage.vector-store:memory}") String vectorStoreType) {
        this.properties = properties;
        this.cacheProvider = cacheProvider;
        this.vectorStoreType = vectorStoreType;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("AI 网关开关状态：master={} | L1 计量={} | L2 稳定性={}（超时 {}s / 每模型限流 {}/s / 重试 {} 次） | "
                        + "L3 缓存={} | L3 Prompt入库={} | L4 指标={}",
                onOff(properties.isEnabled()),
                onOff(properties.isMeteringEnabled()),
                onOff(properties.isResilienceEnabled()),
                properties.getTimeoutSeconds(),
                properties.getRateLimitPerModelPerSecond(),
                properties.getRetryMaxAttempts(),
                onOff(properties.getCache().isEnabled()),
                onOff(properties.isPromptStoreEnabled()),
                onOff(properties.isMetricsEnabled()));

        if (!properties.getCache().isEnabled()) {
            log.info("语义缓存：已通过配置关闭（agent.gateway.cache.enabled=false）");
        } else if (cacheProvider.getIfAvailable() == null) {
            log.info("语义缓存：配置是开启的，但当前不可用——原因是 vector-store={}。"
                            + "语义缓存需要 EmbeddingModel，而它只在 VECTOR_STORE_TYPE=qdrant 时才创建；"
                            + "要用缓存请设置 VECTOR_STORE_TYPE=qdrant 并配好 EMBEDDING_API_KEY。"
                            + "（这不影响正常问答，只是不会命中缓存）",
                    vectorStoreType);
        } else {
            log.info("语义缓存：可用，collection={}，阈值={}",
                    properties.getCache().getCollection(), properties.getCache().getSimilarityThreshold());
        }
    }

    private String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
