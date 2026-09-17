package com.baiyu.agent.gateway;

import com.baiyu.agent.config.ChatModelFactory;
import com.baiyu.agent.config.ModelRegistry;
import com.baiyu.agent.gateway.repository.ModelRouteConfigRepository;
import com.baiyu.agent.gateway.support.GatewayTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.baiyu.agent.gateway.support.GatewayTestSupport.provider;
import static com.baiyu.agent.gateway.support.GatewayTestSupport.responseWithUsage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * L2 的网关级测试：熔断+降级联动、网关层超时、流式重试规则。
 * 验收指标见执行文档第 2 节"L2 稳定性"。
 */
class ModelGatewayImplResilienceTest {

    private static final String PRIMARY = "model-primary";
    private static final String FALLBACK = "model-fallback";

    private GatewayProperties properties;
    private PricingCalculator pricing;
    private UsageRecorder usageRecorder;
    private ModelRegistry modelRegistry;
    private ModelRouteService routeService;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.setMetricsEnabled(false);
        properties.setResilienceEnabled(true);
        properties.setTimeoutSeconds(1);
        properties.setRetryMaxAttempts(1);
        properties.setRateLimitPerModelPerSecond(100);

        GatewayProperties.Route route = new GatewayProperties.Route();
        route.setPrimary(PRIMARY);
        route.setFallbacks(FALLBACK);
        properties.getRoutes().put("kb_qa", route);

        GatewayProperties.Price price = new GatewayProperties.Price();
        price.setPromptPerMillion(1_000_000L);
        price.setCompletionPerMillion(2_000_000L);
        properties.getPricing().getModels().put(PRIMARY, price);
        properties.getPricing().getModels().put(FALLBACK, price);

        pricing = new PricingCalculator(properties);
        usageRecorder = mock(UsageRecorder.class);
        modelRegistry = new ModelRegistry("model-default", "model-pro", "model-fast", "model-vision");
        routeService = new ModelRouteService(mock(ModelRouteConfigRepository.class), properties, modelRegistry);
    }

    private ResilienceSupport resilienceSupport(int slidingWindowSize, int minimumCalls) {
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(slidingWindowSize)
                        .minimumNumberOfCalls(minimumCalls)
                        .failureRateThreshold(50f)
                        .waitDurationInOpenState(Duration.ofSeconds(60))
                        .build());
        RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitRefreshPeriod(Duration.ofSeconds(5))
                        .limitForPeriod(properties.getRateLimitPerModelPerSecond())
                        .timeoutDuration(Duration.ZERO)
                        .build());
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(properties.getRetryMaxAttempts())
                .waitDuration(Duration.ZERO)
                .retryExceptions(Exception.class)
                .build());
        return new ResilienceSupport(circuitBreakerRegistry, rateLimiterRegistry, retryRegistry, properties);
    }

    private ModelGatewayImpl gateway(ChatClient client, ResilienceSupport support) {
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        return new ModelGatewayImpl(client, chatModelFactory, modelRegistry, routeService, properties,
                pricing, usageRecorder, support, provider(null), provider(null), false);
    }

    private static GatewayRequest kbRequest() {
        return GatewayRequest.builder(CallScene.KB_QA)
                .userPrompt("问题").spaceId("space-1").userId("user-1").conversationId("conv-1").build();
    }

    @Test
    void degradedAnswerReturnedWhenResilienceIsOn() {
        ChatClient client = GatewayTestSupport.failingChatClient(new RuntimeException("connection refused"));
        ModelGatewayImpl gateway = gateway(client, resilienceSupport(4, 4));

        GatewayResponse response = gateway.call(kbRequest());

        assertEquals(ModelGatewayImpl.DEGRADED_ANSWER, response.content());
        assertEquals(GatewayResponse.ROUTE_DEGRADED, response.routeType());
    }

    @Test
    void circuitBreakerOpensAndShortCircuitsUpstreamCalls() {
        AtomicInteger upstreamCalls = new AtomicInteger();
        ChatClient client = GatewayTestSupport.failingChatClient(new RuntimeException("upstream down"));
        ResilienceSupport support = resilienceSupport(4, 4);
        ModelGatewayImpl gateway = gateway(client, support);

        // 主模型和降级模型都会失败：4 次请求足以让断路器达到阈值
        for (int i = 0; i < 4; i++) {
            gateway.call(kbRequest());
        }
        upstreamCalls.set(0);

        long start = System.currentTimeMillis();
        GatewayResponse response = gateway.call(kbRequest());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(ModelGatewayImpl.DEGRADED_ANSWER, response.content());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, support.stateOf(PRIMARY));
        // 短路后不再真的发起调用，返回应该很快（验收要求"打开期间仍能在 1 秒内返回"）
        assertEquals(0, upstreamCalls.get());
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 1000,
                "断路器打开后应当在 1 秒内返回降级话术，实测 " + elapsed + "ms");
    }

    @Test
    void gatewayTimeoutIsTreatedAsFailure() {
        ChatClient.ChatClientRequestSpec spec;
        var clientWithSpec = GatewayTestSupport.clientAndSpec();
        spec = clientWithSpec.spec();
        org.mockito.Mockito.when(spec.call()).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return null;
        });

        ModelGatewayImpl gateway = gateway(clientWithSpec.client(), resilienceSupport(10, 5));

        long start = System.currentTimeMillis();
        GatewayResponse response = gateway.call(kbRequest());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(ModelGatewayImpl.DEGRADED_ANSWER, response.content());
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 2500,
                "网关层 1s 超时应该先触发，而不是等到底层 3s 结束，实测 " + elapsed + "ms");
    }

    @Test
    void streamingRetriesWhenNothingWasEmittedYet() {
        properties.setRetryMaxAttempts(2);   // 1 次初始 + 1 次重试
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<ChatResponse> upstream = Flux.defer(() -> {
            if (subscriptions.incrementAndGet() == 1) {
                return Flux.error(new RuntimeException("connection reset before first chunk"));
            }
            return Flux.just(responseWithUsage("回答", 3, 1));
        });

        ModelGatewayImpl gateway = gateway(GatewayTestSupport.streamingChatClient(upstream),
                resilienceSupport(10, 5));

        List<String> texts = gateway.stream(kbRequest()).collectList().block();

        assertEquals(List.of("回答"), texts);
        assertEquals(2, subscriptions.get(), "首片之前的失败应当被重试一次");
    }

    @Test
    void streamingDoesNotRetryAfterFirstChunkWasEmitted() {
        properties.setRetryMaxAttempts(2);   // 允许重试，但已输出内容后必须放弃
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<ChatResponse> upstream = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.concat(
                    Flux.just(responseWithUsage("半句", 3, 1)),
                    Flux.error(new RuntimeException("reset after output")));
        });

        ModelGatewayImpl gateway = gateway(GatewayTestSupport.streamingChatClient(upstream),
                resilienceSupport(10, 5));

        assertThrows(RuntimeException.class,
                () -> gateway.stream(kbRequest()).collectList().block());

        assertEquals(1, subscriptions.get(), "已经输出内容后不能再重试，否则用户会看到重复内容");
    }
}
