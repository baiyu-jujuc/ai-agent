package com.baiyu.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成本计算：单价单位统一为「微元 / 百万 token」，全程整数运算。
 *
 * <p>为什么不用 double：单条记录看起来没问题，但按天聚合几千条之后，
 * 浮点累加误差会让总额和逐条相加对不上，对账就废了。
 */
@Component
public class PricingCalculator {

    private static final Logger log = LoggerFactory.getLogger(PricingCalculator.class);
    private static final long TOKENS_PER_UNIT = 1_000_000L;

    private final GatewayProperties properties;
    private final Set<String> warnedModels = ConcurrentHashMap.newKeySet();

    public PricingCalculator(GatewayProperties properties) {
        this.properties = properties;
    }

    /** 单价快照。未配置单价的模型返回 0，并按模型名只告警一次，避免日志刷屏。 */
    public UnitPrice priceOf(String modelId) {
        GatewayProperties.Price price = properties.getPricing().getModels().get(modelId);
        if (price == null) {
            if (modelId != null && warnedModels.add(modelId)) {
                log.warn("模型 {} 没有配置单价，成本将按 0 记。请在 agent.gateway.pricing.models 里补上。", modelId);
            }
            return new UnitPrice(0L, 0L);
        }
        return new UnitPrice(price.getPromptPerMillion(), price.getCompletionPerMillion());
    }

    public long costMicros(String modelId, int promptTokens, int completionTokens) {
        return costMicros(priceOf(modelId), promptTokens, completionTokens);
    }

    public long costMicros(UnitPrice price, int promptTokens, int completionTokens) {
        long promptCost = promptTokens * price.promptPerMillion() / TOKENS_PER_UNIT;
        long completionCost = completionTokens * price.completionPerMillion() / TOKENS_PER_UNIT;
        return promptCost + completionCost;
    }

    public record UnitPrice(long promptPerMillion, long completionPerMillion) {
    }
}
