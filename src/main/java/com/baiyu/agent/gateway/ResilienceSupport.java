package com.baiyu.agent.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 稳定性装饰器。用<b>编程式</b>而不是 {@code @CircuitBreaker} 注解，原因有三条：
 *
 * <ol>
 *   <li>注解依赖 Spring AOP 代理，同类内部调用（{@code this.xxx()}）会静默失效；</li>
 *   <li>{@code @Retry} / {@code @CircuitBreaker} / {@code @RateLimiter} 同时使用时，
 *       执行顺序由切面顺序决定，不是按注解书写顺序，很难推理；</li>
 *   <li>我们要<b>按模型</b>动态创建实例（key = modelId），编程式天然支持。</li>
 * </ol>
 *
 * <p>固定的调用链：<b>限流 → 熔断 → 重试 → 超时 → 真实调用</b>。
 */
@Component
public class ResilienceSupport {

    private static final Logger log = LoggerFactory.getLogger(ResilienceSupport.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final RetryRegistry retryRegistry;
    private final GatewayProperties properties;

    private final ExecutorService timeoutExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "gateway-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public ResilienceSupport(CircuitBreakerRegistry circuitBreakerRegistry,
                             RateLimiterRegistry rateLimiterRegistry,
                             RetryRegistry retryRegistry,
                             GatewayProperties properties) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.retryRegistry = retryRegistry;
        this.properties = properties;
    }

    public <T> T execute(String modelId, Callable<T> task) {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(modelId);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(modelId);
        Retry retry = retryRegistry.retry(modelId);

        Callable<T> withTimeout = () -> callWithTimeout(task);
        Callable<T> limited = RateLimiter.decorateCallable(rateLimiter, withTimeout);
        Callable<T> breakered = CircuitBreaker.decorateCallable(circuitBreaker, limited);
        Callable<T> retried = Retry.decorateCallable(retry, breakered);
        try {
            return retried.call();
        } catch (Exception e) {
            throw new GatewayInvocationException("模型 " + modelId + " 调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 网关层超时。用独立线程 + {@code Future.get(timeout)} 实现：
     * 这样"卡住不返回"也会被当成一次失败记进熔断器，而不是无限等待。
     */
    private <T> T callWithTimeout(Callable<T> task) throws Exception {
        int timeoutSeconds = Math.max(1, properties.getTimeoutSeconds());
        Future<T> future = timeoutExecutor.submit(task);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("网关层超时：超过 {}s 未返回，主动中断本次调用", timeoutSeconds);
            throw new GatewayTimeoutException("调用超过 " + timeoutSeconds + "s 未返回（网关层超时）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    /** 诊断用：某个模型的断路器当前状态（CLOSED / OPEN / HALF_OPEN）。 */
    public CircuitBreaker.State stateOf(String modelId) {
        return circuitBreakerRegistry.circuitBreaker(modelId).getState();
    }
}
