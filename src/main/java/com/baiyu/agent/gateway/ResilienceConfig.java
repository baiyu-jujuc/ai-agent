package com.baiyu.agent.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * L2 的实例注册表。key 用 modelId，所以"每个模型一套熔断/限流额度"，
 * 而不是整个应用共用一个计数器——这才是"按模型保护上游配额"的意思。
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)          // 最近 10 次调用
                .minimumNumberOfCalls(5)        // 至少 5 次才开始统计，避免启动就被一两次失败打开
                .failureRateThreshold(50f)      // 失败率 50% 打开
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public RateLimiterRegistry rateLimiterRegistry(GatewayProperties properties) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(Math.max(1, properties.getRateLimitPerModelPerSecond()))
                .timeoutDuration(Duration.ZERO)   // 不排队：超了就立刻走降级，而不是把线程堆住
                .build();
        return RateLimiterRegistry.of(config);
    }

    @Bean
    public RetryRegistry retryRegistry(GatewayProperties properties) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(Math.max(1, properties.getRetryMaxAttempts()))
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(Exception.class)
                .build();
        return RetryRegistry.of(config);
    }

    /**
     * 把断路器状态绑到 Micrometer：{@code resilience4j.circuitbreaker.state} 等指标。
     * 验收"熔断能打开"时就是靠它读状态，而不是靠翻日志猜。
     */
    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetrics(CircuitBreakerRegistry registry,
                                                             MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meterRegistry);
        log.info("断路器指标已绑定到 Micrometer：{}", metrics.getClass().getSimpleName());
        return metrics;
    }
}
