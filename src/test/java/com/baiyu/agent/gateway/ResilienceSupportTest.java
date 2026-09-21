package com.baiyu.agent.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 稳定性装饰器的纯单元测试：重试上限、熔断打开、限流拒绝、超时中断。
 * 这里用的是<b>真实的</b> resilience4j 注册表，不是 mock——
 * 因为要验证的正是"库本身的行为"，mock 掉就没意义了。
 */
class ResilienceSupportTest {

    private static final String MODEL = "model-x";

    private GatewayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.setResilienceEnabled(true);
        properties.setTimeoutSeconds(5);
        properties.setRetryMaxAttempts(1);
        properties.setRateLimitPerModelPerSecond(100);
    }

    private ResilienceSupport support(int slidingWindowSize, int minimumCalls, int retryAttempts) {
        properties.setRetryMaxAttempts(retryAttempts);
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(slidingWindowSize)
                        .minimumNumberOfCalls(minimumCalls)
                        .failureRateThreshold(50f)
                        .waitDurationInOpenState(Duration.ofSeconds(60))
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build());
        RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitRefreshPeriod(Duration.ofSeconds(5))
                        .limitForPeriod(Math.max(1, properties.getRateLimitPerModelPerSecond()))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(Math.max(1, retryAttempts))
                .waitDuration(Duration.ZERO)
                .retryExceptions(Exception.class)
                .build());
        return new ResilienceSupport(circuitBreakerRegistry, rateLimiterRegistry, retryRegistry, properties);
    }

    @Test
    void returnsResultWhenCallSucceeds() {
        ResilienceSupport support = support(10, 5, 1);
        assertEquals("ok", support.execute(MODEL, () -> "ok"));
    }

    @Test
    void retriesUpToConfiguredAttempts() {
        ResilienceSupport support = support(10, 5, 3);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(GatewayInvocationException.class, () -> support.execute(MODEL, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }));

        // resilience4j 的 maxAttempts=3 表示"最多尝试 3 次"（含首次）
        assertEquals(3, attempts.get());
    }

    @Test
    void stopsRetryingAfterSuccess() {
        ResilienceSupport support = support(10, 5, 3);
        AtomicInteger attempts = new AtomicInteger();

        String result = support.execute(MODEL, () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("first attempt fails");
            }
            return "second attempt ok";
        });

        assertEquals("second attempt ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void opensCircuitBreakerAfterFailureThresholdAndShortCircuits() {
        ResilienceSupport support = support(4, 4, 1);
        AtomicInteger attempts = new AtomicInteger();

        for (int i = 0; i < 4; i++) {
            assertThrows(GatewayInvocationException.class, () -> support.execute(MODEL, () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("upstream down");
            }));
        }

        assertEquals(4, attempts.get());
        assertEquals(CircuitBreaker.State.OPEN, support.stateOf(MODEL), "失败率达到阈值后应进入打开态");

        // 打开状态下请求被直接短路：任务体不再执行（这正是"保护自己线程"的意义）
        assertThrows(GatewayInvocationException.class, () -> support.execute(MODEL, () -> {
            attempts.incrementAndGet();
            return "should not run";
        }));
        assertEquals(4, attempts.get(), "断路打开期间不应该真的调用上游");
    }

    @Test
    void rateLimiterRejectsCallsBeyondLimit() {
        properties.setRateLimitPerModelPerSecond(1);
        ResilienceSupport support = support(100, 100, 1);

        assertEquals("first", support.execute(MODEL, () -> "first"));
        // 限流周期 5 秒、额度 1：第二个请求立刻被拒，而不是排队等待
        assertThrows(GatewayInvocationException.class, () -> support.execute(MODEL, () -> "second"));
    }

    @Test
    void timeoutAbortsSlowCall() {
        properties.setTimeoutSeconds(1);
        ResilienceSupport support = support(10, 5, 1);

        GatewayInvocationException error = assertThrows(GatewayInvocationException.class,
                () -> support.execute(MODEL, () -> {
                    Thread.sleep(3000);
                    return "too late";
                }));

        assertInstanceOf(GatewayTimeoutException.class, error.getCause(),
                "超时应该以 GatewayTimeoutException 的形式暴露出来");
    }
}
