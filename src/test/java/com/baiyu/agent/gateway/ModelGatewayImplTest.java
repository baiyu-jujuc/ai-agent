package com.baiyu.agent.gateway;

import com.baiyu.agent.config.ChatModelFactory;
import com.baiyu.agent.config.ModelRegistry;
import com.baiyu.agent.gateway.entity.LlmUsageRecord;
import com.baiyu.agent.gateway.repository.ModelRouteConfigRepository;
import com.baiyu.agent.gateway.support.GatewayTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;

import static com.baiyu.agent.gateway.support.GatewayTestSupport.clientAndSpec;
import static com.baiyu.agent.gateway.support.GatewayTestSupport.provider;
import static com.baiyu.agent.gateway.support.GatewayTestSupport.responseWithUsage;
import static com.baiyu.agent.gateway.support.GatewayTestSupport.responseWithoutUsage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关的核心行为测试：计量覆盖率、成本计算、降级、缓存钩子、流式记账。
 * 每条断言都能对应到执行文档第 2 节的某条验收指标。
 */
class ModelGatewayImplTest {

    private static final String PRIMARY = "model-primary";
    private static final String FALLBACK = "model-fallback";

    private GatewayProperties properties;
    private PricingCalculator pricing;
    private UsageRecorder usageRecorder;
    private ModelRegistry modelRegistry;
    private ChatModelFactory chatModelFactory;
    private ModelRouteService routeService;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.setMetricsEnabled(false);

        GatewayProperties.Route kbRoute = new GatewayProperties.Route();
        kbRoute.setPrimary(PRIMARY);
        kbRoute.setFallbacks(FALLBACK);
        properties.getRoutes().put("kb_qa", kbRoute);

        // 单价：prompt 1 微元/token，completion 2 微元/token（便于心算复算）
        properties.getPricing().getModels().put(PRIMARY, price(1_000_000L, 2_000_000L));
        properties.getPricing().getModels().put(FALLBACK, price(1_000_000L, 2_000_000L));

        pricing = new PricingCalculator(properties);
        usageRecorder = mock(UsageRecorder.class);
        modelRegistry = new ModelRegistry("model-default", "model-pro", "model-fast", "model-vision");
        chatModelFactory = mock(ChatModelFactory.class);
        routeService = new ModelRouteService(mock(ModelRouteConfigRepository.class), properties, modelRegistry);
    }

    private static GatewayProperties.Price price(long prompt, long completion) {
        GatewayProperties.Price price = new GatewayProperties.Price();
        price.setPromptPerMillion(prompt);
        price.setCompletionPerMillion(completion);
        return price;
    }

    private ModelGatewayImpl gateway(ChatClient client, boolean allowClientModelKey) {
        return gateway(client, allowClientModelKey, provider(null), provider(null));
    }

    private ModelGatewayImpl gateway(ChatClient client, boolean allowClientModelKey,
                                     ObjectProvider<SemanticCache> cache, ObjectProvider<MeterRegistry> meters) {
        return new ModelGatewayImpl(client, chatModelFactory, modelRegistry, routeService, properties, pricing,
                usageRecorder, null, cache, meters, allowClientModelKey);
    }

    private static GatewayRequest kbRequest(String question) {
        return GatewayRequest.builder(CallScene.KB_QA)
                .userPrompt(question)
                .spaceId("space-1")
                .userId("user-1")
                .conversationId("conv-1")
                .build();
    }

    // ---------------------------------------------------------------- 计量与成本

    @Test
    void callRecordsProviderUsageAndCost() {
        ChatClient client = GatewayTestSupport.chatClient(responseWithUsage("答案", 10, 5));
        ModelGatewayImpl gateway = gateway(client, false);

        GatewayResponse response = gateway.call(kbRequest("问题"));

        assertEquals("答案", response.content());
        assertEquals(PRIMARY, response.modelUsed());
        assertEquals(GatewayResponse.ROUTE_PRIMARY, response.routeType());
        assertEquals(TokenUsage.SOURCE_PROVIDER, response.usageSource());
        // 10 * 1 微元 + 5 * 2 微元 = 20 微元
        assertEquals(20L, response.costMicros());

        LlmUsageRecord record = captureSingleRecord();
        assertEquals(PRIMARY, record.getModelId());
        assertEquals("KB_QA", record.getScene());
        assertEquals(GatewayResponse.ROUTE_PRIMARY, record.getRouteType());
        assertEquals(10, record.getPromptTokens());
        assertEquals(5, record.getCompletionTokens());
        assertEquals(15, record.getTotalTokens());
        assertEquals(TokenUsage.SOURCE_PROVIDER, record.getUsageSource());
        assertEquals(1_000_000L, record.getPromptUnitPrice());
        assertEquals(2_000_000L, record.getCompletionUnitPrice());
        assertEquals(20L, record.getCostMicros());
        assertEquals("SUCCESS", record.getOutcome());
        assertFalse(record.isCacheHit());
        assertEquals("space-1", record.getSpaceId());
        assertEquals("user-1", record.getUserId());
        assertEquals("conv-1", record.getConversationId());
        assertNotNull(record.getCreatedAt());
        assertNotNull(record.getTraceId());
    }

    @Test
    void callMarksEstimatedWhenProviderReturnsNoUsage() {
        ChatClient client = GatewayTestSupport.chatClient(responseWithoutUsage("答案"));
        ModelGatewayImpl gateway = gateway(client, false);

        GatewayResponse response = gateway.call(kbRequest("这是一个问题"));

        assertEquals(TokenUsage.SOURCE_ESTIMATED, response.usageSource());
        assertTrue(response.promptTokens() > 0, "估算的 prompt token 应该大于 0");
        assertEquals(TokenUsage.SOURCE_ESTIMATED, captureSingleRecord().getUsageSource());
    }

    @Test
    void meteringDisabledWritesNothing() {
        properties.setMeteringEnabled(false);
        ChatClient client = GatewayTestSupport.chatClient(responseWithUsage("答案", 10, 5));
        ModelGatewayImpl gateway = gateway(client, false);

        gateway.call(kbRequest("问题"));

        verify(usageRecorder, never()).record(any());
    }

    @Test
    void gatewayDisabledBypassesMetering() {
        properties.setEnabled(false);
        ChatClient client = GatewayTestSupport.chatClient(responseWithUsage("答案", 10, 5));
        ModelGatewayImpl gateway = gateway(client, false);

        GatewayResponse response = gateway.call(kbRequest("问题"));

        assertEquals("答案", response.content());
        assertEquals(0L, response.costMicros());
        verify(usageRecorder, never()).record(any());
    }

    // ---------------------------------------------------------------- 降级

    @Test
    void fallsBackToSecondaryModelWhenPrimaryFails() {
        var clientWithSpec = clientAndSpec();
        ChatResponse fallbackResponse = responseWithUsage("备用模型的答案", 4, 3);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(callSpec.chatResponse()).thenReturn(fallbackResponse);
        when(clientWithSpec.spec().call())
                .thenThrow(new RuntimeException("connection refused"))
                .thenReturn(callSpec);

        ModelGatewayImpl gateway = gateway(clientWithSpec.client(), false);
        GatewayResponse response = gateway.call(kbRequest("问题"));

        assertEquals("备用模型的答案", response.content());
        assertEquals(FALLBACK, response.modelUsed());
        assertEquals(GatewayResponse.ROUTE_FALLBACK, response.routeType());

        List<LlmUsageRecord> records = captureRecords(2);
        assertEquals(PRIMARY, records.get(0).getModelId());
        assertEquals("ERROR", records.get(0).getOutcome());
        assertEquals(FALLBACK, records.get(1).getModelId());
        assertEquals("SUCCESS", records.get(1).getOutcome());
        assertEquals(GatewayResponse.ROUTE_FALLBACK, records.get(1).getRouteType());
        // 4 * 1 + 3 * 2 = 10 微元
        assertEquals(10L, records.get(1).getCostMicros());
    }

    @Test
    void returnsDegradedAnswerWhenAllCandidatesFail() {
        ChatClient client = GatewayTestSupport.failingChatClient(new RuntimeException("boom"));
        ModelGatewayImpl gateway = gateway(client, false);

        GatewayResponse response = gateway.call(kbRequest("问题"));

        assertEquals(ModelGatewayImpl.DEGRADED_ANSWER, response.content());
        assertEquals(GatewayResponse.ROUTE_DEGRADED, response.routeType());
        assertTrue(response.content().contains("请稍后重试"));
        assertFalse(response.content().contains("boom"), "降级话术不能暴露上游错误");

        List<LlmUsageRecord> records = captureRecords(3);
        assertEquals("DEGRADED", records.get(2).getOutcome());
        assertEquals(GatewayResponse.ROUTE_DEGRADED, records.get(2).getRouteType());
    }

    // ---------------------------------------------------------------- 客户端自带 Key

    @Test
    void clientApiKeyIgnoredWhenFeatureDisabled() {
        ChatClient client = GatewayTestSupport.chatClient(responseWithUsage("答案", 1, 1));
        ModelGatewayImpl gateway = gateway(client, false);

        gateway.call(GatewayRequest.builder(CallScene.KB_QA)
                .userPrompt("问题").clientApiKey("sk-user-key").build());

        verify(chatModelFactory, never()).createChatModel(anyString(), anyString());
    }

    @Test
    void clientApiKeyUsedWhenFeatureEnabled() {
        ChatClient defaultClient = GatewayTestSupport.chatClient(responseWithUsage("默认", 1, 1));
        ModelGatewayImpl gateway = gateway(defaultClient, true);
        when(chatModelFactory.createChatModel("sk-user-key", PRIMARY))
                .thenReturn(mock(org.springframework.ai.chat.model.ChatModel.class));

        try {
            gateway.call(GatewayRequest.builder(CallScene.KB_QA)
                    .userPrompt("问题").clientApiKey("sk-user-key").build());
        } catch (Exception ignored) {
            // 用 mock 的 ChatModel 建出来的 ChatClient 无法真正处理请求，这里只验证工厂被调用
        }

        verify(chatModelFactory).createChatModel("sk-user-key", PRIMARY);
    }

    // ---------------------------------------------------------------- 结构化输出

    @Test
    void callEntityRecordsRoutingScene() {
        ChatClient client = GatewayTestSupport.entityChatClient(responseWithUsage("{\"agent\":\"code\"}", 8, 4),
                new CoordinatorRoutingStub("code"));
        ModelGatewayImpl gateway = gateway(client, false);

        CoordinatorRoutingStub result = gateway.callEntity(
                GatewayRequest.builder(CallScene.AGENT_ROUTING).userPrompt("写个 Java 函数").build(),
                CoordinatorRoutingStub.class);

        assertEquals("code", result.agent());
        LlmUsageRecord record = captureSingleRecord();
        assertEquals("AGENT_ROUTING", record.getScene());
        assertEquals(8, record.getPromptTokens());
    }

    /** 结构化输出的测试替身（真实场景对应 CoordinatorAgent.RoutingDecision）。 */
    record CoordinatorRoutingStub(String agent) {
    }

    // ---------------------------------------------------------------- 流式

    @Test
    void streamingUsesLastChunkUsageNotSumOfChunks() {
        Flux<ChatResponse> chunks = Flux.just(
                responseWithUsage("", 0, 0),
                responseWithUsage("收到", 0, 0),
                responseWithUsage("", 10, 7));
        ModelGatewayImpl gateway = gateway(GatewayTestSupport.streamingChatClient(chunks), false);

        List<String> texts = gateway.stream(kbRequest("问题")).collectList().block();

        assertEquals(List.of("收到"), texts);
        LlmUsageRecord record = captureSingleRecord();
        assertEquals(TokenUsage.SOURCE_PROVIDER, record.getUsageSource());
        assertEquals(10, record.getPromptTokens());
        assertEquals(7, record.getCompletionTokens());
        assertEquals("SUCCESS", record.getOutcome());
        assertEquals("KB_QA", record.getScene(), "记录的场景来自请求，不是流式通道");
    }

    @Test
    void streamingEstimatesWhenNoChunkCarriesUsage() {
        Flux<ChatResponse> chunks = Flux.just(
                responseWithoutUsage("你好"),
                responseWithoutUsage("世界"));
        ModelGatewayImpl gateway = gateway(GatewayTestSupport.streamingChatClient(chunks), false);

        gateway.stream(kbRequest("请生成一段话")).collectList().block();

        LlmUsageRecord record = captureSingleRecord();
        assertEquals(TokenUsage.SOURCE_ESTIMATED, record.getUsageSource());
        assertTrue(record.getPromptTokens() > 0);
        assertTrue(record.getCompletionTokens() > 0);
    }

    @Test
    void streamingErrorStillRecordsUsage() {
        Flux<ChatResponse> chunks = Flux.concat(
                Flux.just(responseWithUsage("半句", 3, 1)),
                Flux.error(new RuntimeException("upstream reset")));
        ModelGatewayImpl gateway = gateway(GatewayTestSupport.streamingChatClient(chunks), false);

        try {
            gateway.stream(kbRequest("问题")).collectList().block();
        } catch (Exception ignored) {
            // 上游异常会传给下游，这是原有行为
        }

        LlmUsageRecord record = captureSingleRecord();
        assertEquals("ERROR", record.getOutcome());
        assertEquals(3, record.getPromptTokens());
    }

    @Test
    void streamingCancelStillRecordsUsage() {
        Flux<ChatResponse> chunks = Flux.just(
                responseWithUsage("第一段", 6, 2),
                responseWithUsage("第二段", 6, 4));
        ModelGatewayImpl gateway = gateway(GatewayTestSupport.streamingChatClient(chunks), false);

        // take(1) 会向上游发 cancel：模拟"用户中途关掉页面"
        gateway.stream(kbRequest("问题")).take(1).collectList().block();

        LlmUsageRecord record = captureSingleRecord();
        assertEquals("ERROR", record.getOutcome(), "取消不是完成，信号类型要如实记录");
        assertEquals(6, record.getPromptTokens());
    }

    // ---------------------------------------------------------------- 缓存钩子（L3 只在网关层接入）

    @Test
    void cacheHitReturnsCachedAnswerWithoutCallingModel() {
        SemanticCache cache = mock(SemanticCache.class);
        when(cache.lookup(eq("space-1"), eq("问题"), eq(PRIMARY)))
                .thenReturn(java.util.Optional.of(new SemanticCache.CachedAnswer("缓存答案", PRIMARY, "kb_qa_plain", 1)));

        ChatClient client = mock(ChatClient.class);   // 不该被用到
        ModelGatewayImpl gateway = gateway(client, false, provider(cache), provider(null));

        GatewayResponse response = gateway.call(kbRequest("问题"));

        assertEquals("缓存答案", response.content());
        assertEquals(GatewayResponse.ROUTE_CACHE, response.routeType());
        assertTrue(response.cacheHit());
        assertEquals(0L, response.costMicros());
        verify(client, never()).prompt();

        LlmUsageRecord record = captureSingleRecord();
        assertEquals(GatewayResponse.ROUTE_CACHE, record.getRouteType());
        assertTrue(record.isCacheHit());
        assertEquals(0L, record.getCostMicros());
    }

    @Test
    void refusalAnswerIsNotCached() {
        SemanticCache cache = mock(SemanticCache.class);
        when(cache.lookup(anyString(), anyString(), anyString())).thenReturn(java.util.Optional.empty());
        ChatClient client = GatewayTestSupport.chatClient(
                responseWithUsage("知识库中未找到相关内容，请补充文档。", 5, 5));
        ModelGatewayImpl gateway = gateway(client, false, provider(cache), provider(null));

        gateway.call(kbRequest("库里没有的问题"));

        verify(cache, never()).store(any(), any(), any(), any(), any(), any());
    }

    @Test
    void normalAnswerIsWrittenToCache() {
        SemanticCache cache = mock(SemanticCache.class);
        when(cache.lookup(anyString(), anyString(), anyString())).thenReturn(java.util.Optional.empty());
        ChatClient client = GatewayTestSupport.chatClient(responseWithUsage("正常答案", 5, 5));
        ModelGatewayImpl gateway = gateway(client, false, provider(cache), provider(null));

        gateway.call(kbRequest("问题"));

        verify(cache).store(eq("space-1"), eq("问题"), eq(PRIMARY), eq("正常答案"), any(), any());
    }

    @Test
    void cacheSkippedWhenClientProvidesOwnKey() {
        SemanticCache cache = mock(SemanticCache.class);
        ChatClient client = GatewayTestSupport.chatClient(responseWithUsage("答案", 1, 1));
        ModelGatewayImpl gateway = gateway(client, false, provider(cache), provider(null));

        gateway.call(GatewayRequest.builder(CallScene.KB_QA)
                .userPrompt("问题").spaceId("space-1").clientApiKey("sk-user-key").build());

        verify(cache, never()).lookup(any(), any(), any());
        verify(cache, never()).store(any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 路由解析

    @Test
    void routeResolutionPrefersRequestOverrideThenSceneThenDefault() {
        ModelGatewayImpl gateway = gateway(GatewayTestSupport.chatClient(responseWithUsage("x", 1, 1)), false);

        assertEquals(List.of(PRIMARY, FALLBACK), gateway.resolveCandidates(kbRequest("问题")));

        GatewayRequest withOverride = GatewayRequest.builder(CallScene.KB_QA)
                .userPrompt("问题").modelOverride("explicit-model").build();
        assertEquals(List.of("explicit-model", FALLBACK), gateway.resolveCandidates(withOverride));

        GatewayRequest chat = GatewayRequest.builder(CallScene.CHAT_SIMPLE).userPrompt("hi").build();
        assertEquals(List.of("model-default"), gateway.resolveCandidates(chat));
    }

    // ---------------------------------------------------------------- 指标

    @Test
    void metricsAreRegisteredWhenEnabled() {
        properties.setMetricsEnabled(true);
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatClient client = GatewayTestSupport.chatClient(responseWithUsage("答案", 10, 5));
        ModelGatewayImpl gateway = gateway(client, false, provider(null), provider(registry));

        gateway.call(kbRequest("问题"));

        assertNotNull(registry.find("llm_call_total")
                .tag("model", PRIMARY).tag("scene", "KB_QA").tag("outcome", "SUCCESS").counter());
        assertEquals(10d, registry.find("llm_token_total")
                .tag("model", PRIMARY).tag("type", "prompt").counter().count());
        assertEquals(20d, registry.find("llm_cost_micros_total")
                .tag("model", PRIMARY).counter().count());
    }

    @Test
    void metricsDoNotUseHighCardinalityTags() {
        properties.setMetricsEnabled(true);
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatClient client = GatewayTestSupport.chatClient(responseWithUsage("答案", 10, 5));
        ModelGatewayImpl gateway = gateway(client, false, provider(null), provider(registry));

        gateway.call(kbRequest("问题"));

        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertFalse("userId".equals(tag.getKey()), "指标 tag 不能放 userId");
            assertFalse("spaceId".equals(tag.getKey()), "指标 tag 不能放 spaceId");
            assertFalse("conversationId".equals(tag.getKey()), "指标 tag 不能放 conversationId");
        }));
    }

    // ---------------------------------------------------------------- 工具方法

    private LlmUsageRecord captureSingleRecord() {
        return captureRecords(1).get(0);
    }

    private List<LlmUsageRecord> captureRecords(int expectedCount) {
        ArgumentCaptor<LlmUsageRecord> captor = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(usageRecorder, times(expectedCount)).record(captor.capture());
        return captor.getAllValues();
    }
}
