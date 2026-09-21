package com.baiyu.agent.gateway;

import com.baiyu.agent.config.ChatModelFactory;
import com.baiyu.agent.config.ModelRegistry;
import com.baiyu.agent.gateway.entity.LlmUsageRecord;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型调用网关：全仓库唯一的模型出口。
 *
 * <p>调用链顺序（顺序本身就是这段代码的价值，写在代码里而不是靠注解推断）：
 * <pre>
 *   语义缓存 → 逐候选模型（限流 → 熔断 → 重试 → 超时 → 真实调用）→ 计量 → 指标
 * </pre>
 *
 * <p>每一层都受 {@link GatewayProperties} 的独立开关控制，出问题时能立刻判断是哪一层引起的。
 */
@Service
public class ModelGatewayImpl implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ModelGatewayImpl.class);

    /** 兜底话术：可读、不暴露内部错误与堆栈。 */
    static final String DEGRADED_ANSWER = "当前模型服务繁忙，请稍后重试。";

    private static final String OUTCOME_SUCCESS = "SUCCESS";
    private static final String OUTCOME_ERROR = "ERROR";
    private static final String OUTCOME_DEGRADED = "DEGRADED";

    /** 拒答的判定关键词：这类回答不进缓存，否则用户上传新文档后仍会拿到旧拒答。 */
    private static final List<String> REFUSAL_MARKERS = List.of(
            "知识库中未找到相关内容",
            "知识库中未找到与当前问题直接相关的内容",
            "当前知识空间中没有找到");

    private final ChatClient defaultChatClient;
    private final ChatModelFactory chatModelFactory;
    private final ModelRegistry modelRegistry;
    private final ModelRouteService routeService;
    private final GatewayProperties properties;
    private final PricingCalculator pricing;
    private final UsageRecorder usageRecorder;
    private final ResilienceSupport resilienceSupport;
    private final ObjectProvider<SemanticCache> cacheProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final boolean allowClientModelKey;

    public ModelGatewayImpl(ChatClient defaultChatClient,
                            ChatModelFactory chatModelFactory,
                            ModelRegistry modelRegistry,
                            ModelRouteService routeService,
                            GatewayProperties properties,
                            PricingCalculator pricing,
                            UsageRecorder usageRecorder,
                            ResilienceSupport resilienceSupport,
                            ObjectProvider<SemanticCache> cacheProvider,
                            ObjectProvider<MeterRegistry> meterRegistryProvider,
                            @Value("${agent.security.allow-client-model-key:false}") boolean allowClientModelKey) {
        this.defaultChatClient = defaultChatClient;
        this.chatModelFactory = chatModelFactory;
        this.modelRegistry = modelRegistry;
        this.routeService = routeService;
        this.properties = properties;
        this.pricing = pricing;
        this.usageRecorder = usageRecorder;
        this.resilienceSupport = resilienceSupport;
        this.cacheProvider = cacheProvider;
        this.meterRegistryProvider = meterRegistryProvider;
        this.allowClientModelKey = allowClientModelKey;
        log.info("ModelGateway 初始化：enabled={}, metering={}, resilience={}, metrics={}, promptStore={}, "
                        + "cache={}, timeout={}s",
                properties.isEnabled(), properties.isMeteringEnabled(), properties.isResilienceEnabled(),
                properties.isMetricsEnabled(), properties.isPromptStoreEnabled(),
                properties.getCache().isEnabled(), properties.getTimeoutSeconds());
    }

    // ------------------------------------------------------------------ 非流式

    @Override
    public GatewayResponse call(GatewayRequest request) {
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();

        if (!properties.isEnabled()) {
            return bypassCall(request, start);
        }

        List<String> candidates = resolveCandidates(request);
        String primaryModel = candidates.get(0);

        // 1) 语义缓存：命中就不调用模型，token 成本为 0
        SemanticCache cache = usableCache(request);
        if (cache != null) {
            try {
                Optional<SemanticCache.CachedAnswer> hit =
                        cache.lookup(request.spaceId(), request.userPrompt(), primaryModel);
                if (hit.isPresent()) {
                    SemanticCache.CachedAnswer cached = hit.get();
                    long latency = System.currentTimeMillis() - start;
                    log.info("语义缓存命中：space={}, model={}", request.spaceId(), cached.modelId());
                    recordUsage(request, traceId, cached.modelId(), GatewayResponse.ROUTE_CACHE, true,
                            TokenUsage.empty(), 0L, latency, OUTCOME_SUCCESS, null);
                    recordMetrics(cached.modelId(), request.scene(), OUTCOME_SUCCESS, 0, 0, 0L, latency, false);
                    return new GatewayResponse(cached.answer(), cached.modelId(), GatewayResponse.ROUTE_CACHE,
                            true, TokenUsage.SOURCE_ESTIMATED, 0, 0, 0L, latency,
                            cached.promptKey(), cached.promptVersion());
                }
            } catch (Exception e) {
                // 缓存只是加速手段，坏了不能影响正常问答
                log.warn("语义缓存查询失败（忽略，继续正常调用）：{}", e.toString());
            }
        }

        // 2) 逐个候选模型尝试：第一个是主模型，其余是降级链
        for (int i = 0; i < candidates.size(); i++) {
            String modelId = candidates.get(i);
            boolean fallback = i > 0;
            try {
                ChatResponse response = invokeOrThrow(modelId, request);
                TokenUsage usage = extractUsage(response, request);
                long costMicros = pricing.costMicros(modelId, usage.promptTokens(), usage.completionTokens());
                long latency = System.currentTimeMillis() - start;
                String answer = textOf(response);

                if (fallback) {
                    log.warn("主模型 {} 不可用，已降级到 {}（scene={}）", primaryModel, modelId, request.scene());
                }
                if (cache != null && !isRefusal(answer)) {
                    tryStoreCache(cache, request, modelId, answer);
                }

                recordUsage(request, traceId, modelId,
                        fallback ? GatewayResponse.ROUTE_FALLBACK : GatewayResponse.ROUTE_PRIMARY, false,
                        usage, costMicros, latency, OUTCOME_SUCCESS, null);
                recordMetrics(modelId, request.scene(), OUTCOME_SUCCESS,
                        usage.promptTokens(), usage.completionTokens(), costMicros, latency, fallback);

                return new GatewayResponse(answer, modelId,
                        fallback ? GatewayResponse.ROUTE_FALLBACK : GatewayResponse.ROUTE_PRIMARY, false,
                        usage.source(), usage.promptTokens(), usage.completionTokens(), costMicros, latency,
                        request.promptKey(), request.promptVersion());
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                log.warn("模型 {} 调用失败（scene={}），准备尝试下一个候选：{}",
                        modelId, request.scene(), e.toString());
                // 失败也要留痕，否则"失败率"算不出来
                recordUsage(request, traceId, modelId,
                        fallback ? GatewayResponse.ROUTE_FALLBACK : GatewayResponse.ROUTE_PRIMARY, false,
                        TokenUsage.empty(), 0L, latency, OUTCOME_ERROR, e.getClass().getSimpleName());
                recordMetrics(modelId, request.scene(), OUTCOME_ERROR, 0, 0, 0L, latency, fallback);
            }
        }

        // 3) 全部候选失败 → 兜底话术（不返回堆栈、不暴露上游报错）
        long latency = System.currentTimeMillis() - start;
        log.error("所有候选模型均不可用（scene={}），返回降级话术", request.scene());
        recordUsage(request, traceId, "none", GatewayResponse.ROUTE_DEGRADED, false,
                TokenUsage.empty(), 0L, latency, OUTCOME_DEGRADED, null);
        recordMetrics("none", request.scene(), OUTCOME_DEGRADED, 0, 0, 0L, latency, true);
        return GatewayResponse.degraded(DEGRADED_ANSWER, request.promptKey(), request.promptVersion(), latency);
    }

    // ------------------------------------------------------------------ 结构化输出

    @Override
    public <T> T callEntity(GatewayRequest request, Class<T> responseType) {
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        List<String> candidates = resolveCandidates(request);

        if (!properties.isEnabled()) {
            return buildRequestSpec(defaultChatClient, request, candidates.get(0))
                    .call()
                    .entity(responseType);
        }

        Exception lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            String modelId = candidates.get(i);
            boolean fallback = i > 0;
            try {
                ChatClient client = resolveClient(request);
                Callable<org.springframework.ai.chat.client.ResponseEntity<ChatResponse, T>> task = () ->
                        buildRequestSpec(client, request, modelId).call().responseEntity(responseType);
                org.springframework.ai.chat.client.ResponseEntity<ChatResponse, T> response =
                        invokeWithResilience(modelId, task);

                TokenUsage usage = extractUsage(response.response(), request);
                long costMicros = pricing.costMicros(modelId, usage.promptTokens(), usage.completionTokens());
                long latency = System.currentTimeMillis() - start;
                recordUsage(request, traceId, modelId,
                        fallback ? GatewayResponse.ROUTE_FALLBACK : GatewayResponse.ROUTE_PRIMARY, false,
                        usage, costMicros, latency, OUTCOME_SUCCESS, null);
                recordMetrics(modelId, request.scene(), OUTCOME_SUCCESS,
                        usage.promptTokens(), usage.completionTokens(), costMicros, latency, fallback);
                return response.entity();
            } catch (Exception e) {
                lastError = e;
                log.warn("结构化调用失败（model={}, scene={}）：{}", modelId, request.scene(), e.toString());
                recordUsage(request, traceId, modelId,
                        fallback ? GatewayResponse.ROUTE_FALLBACK : GatewayResponse.ROUTE_PRIMARY, false,
                        TokenUsage.empty(), 0L, System.currentTimeMillis() - start, OUTCOME_ERROR,
                        e.getClass().getSimpleName());
            }
        }
        throw new GatewayInvocationException("所有候选模型的结构化调用均失败: "
                + (lastError == null ? "unknown" : lastError.getMessage()), lastError);
    }

    // ------------------------------------------------------------------ 流式

    @Override
    public Flux<String> stream(GatewayRequest request) {
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        String modelId = resolveCandidates(request).get(0);
        ChatClient client = resolveClient(request);

        AtomicBoolean started = new AtomicBoolean(false);
        AtomicReference<ChatResponse> lastChunk = new AtomicReference<>();
        StringBuilder collected = new StringBuilder();

        Flux<ChatResponse> upstream = buildRequestSpec(client, request, modelId).stream().chatResponse();
        if (properties.isResilienceEnabled()) {
            upstream = applyStreamRetry(upstream, started);
        }

        return upstream
                .doOnNext(chunk -> {
                    started.set(true);
                    lastChunk.set(chunk);
                    String text = textOf(chunk);
                    if (text != null) {
                        collected.append(text);
                    }
                })
                .doFinally(signal -> {
                    // doFinally 而不是 doOnComplete：用户关页面时流是"取消"，用 doOnComplete 会整块漏记
                    TokenUsage usage = extractStreamingUsage(lastChunk.get(), collected.toString(), request);
                    long costMicros;
                    try {
                        costMicros = pricing.costMicros(modelId, usage.promptTokens(), usage.completionTokens());
                    } catch (Exception e) {
                        costMicros = 0L;
                    }
                    long latency = System.currentTimeMillis() - start;
                    String outcome = signal == SignalType.ON_COMPLETE ? OUTCOME_SUCCESS : OUTCOME_ERROR;
                    recordUsage(request, traceId, modelId, GatewayResponse.ROUTE_PRIMARY, false,
                            usage, costMicros, latency, outcome, signal == SignalType.ON_COMPLETE ? null : signal.name());
                    recordMetrics(modelId, request.scene(), outcome,
                            usage.promptTokens(), usage.completionTokens(), costMicros, latency, false);
                })
                .map(ModelGatewayImpl::textOf)
                // 过滤 null 与空串：末片只带 usage、不带文本，这种空片不该作为 SSE 数据发给前端
                .filter(text -> text != null && !text.isEmpty());
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 稳定性入口：限流 → 熔断 → 重试 → 超时 → 真实调用。
     * 开关 {@code agent.gateway.resilience-enabled} 默认 false，关了就是直连（L1 行为）。
     */
    private <T> T invokeWithResilience(String modelId, Callable<T> task) throws Exception {
        if (properties.isResilienceEnabled() && resilienceSupport != null) {
            return resilienceSupport.execute(modelId, task);
        }
        return task.call();
    }

    /**
     * 流式重试规则：<b>只有在还没向客户端发出任何内容时才允许重试</b>。
     *
     * <p>一旦已经有 token 发出去，再重订阅上游会让用户看到重复的半句话——
     * 这是流式改造里最容易踩的坑（见执行文档第 8 节第 4 条）。
     */
    Flux<ChatResponse> applyStreamRetry(Flux<ChatResponse> upstream, AtomicBoolean started) {
        long maxRetries = Math.max(0, properties.getRetryMaxAttempts() - 1L);
        if (maxRetries == 0) {
            return upstream;
        }
        return upstream.retryWhen(reactor.util.retry.Retry.fixedDelay(maxRetries, Duration.ofMillis(200))
                .filter(error -> !started.get()));
    }

    /** 按场景解析候选模型：主模型 + 降级链（配置来源见 ModelRouteService 的三级回退）。 */
    List<String> resolveCandidates(GatewayRequest request) {
        return routeService.candidates(routeKeyFor(request.scene()), request.modelOverride());
    }

    String routeKeyFor(CallScene scene) {
        if (scene == null) {
            return "default_chat";
        }
        String key = scene.name().toLowerCase(java.util.Locale.ROOT);
        if (properties.getRoutes().containsKey(key)) {
            return key;
        }
        return scene == CallScene.KB_QA ? "kb_qa" : "default_chat";
    }

    private ChatClient.ChatClientRequestSpec buildRequestSpec(ChatClient client, GatewayRequest request, String modelId) {
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            spec = spec.system(request.systemPrompt());
        }
        if (request.hasMessages()) {
            spec = spec.messages(request.messages());
        }
        if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
            spec = spec.user(request.userPrompt());
        }
        if (request.hasTools()) {
            spec = spec.tools(request.tools().toArray());
        }
        ChatOptions.Builder options = ChatOptions.builder().model(modelId);
        if (request.temperature() != null) {
            options.temperature(request.temperature());
        }
        return spec.options(options.build());
    }

    /**
     * 客户端自带 Key 的旁路保持不变：只有在 {@code allow-client-model-key=true} 且请求真的带了 Key 时才走工厂，
     * 且只用于本次请求，不落库、不打印。
     */
    private ChatClient resolveClient(GatewayRequest request) {
        if (request.hasClientApiKey() && allowClientModelKey) {
            String modelId = resolveCandidates(request).get(0);
            return ChatClient.builder(chatModelFactory.createChatModel(request.clientApiKey(), modelId)).build();
        }
        return defaultChatClient;
    }

    private GatewayResponse bypassCall(GatewayRequest request, long start) {
        String modelId = resolveCandidates(request).get(0);
        ChatResponse response = buildRequestSpec(resolveClient(request), request, modelId).call().chatResponse();
        return new GatewayResponse(textOf(response), modelId, GatewayResponse.ROUTE_PRIMARY, false,
                TokenUsage.SOURCE_ESTIMATED, 0, 0, 0L, System.currentTimeMillis() - start,
                request.promptKey(), request.promptVersion());
    }

    private ChatResponse invokeOrThrow(String modelId, GatewayRequest request) throws Exception {
        ChatClient client = resolveClient(request);
        return invokeWithResilience(modelId, () -> buildRequestSpec(client, request, modelId).call().chatResponse());
    }

    /** 真实优先、估算兜底：把"估算值"伪装成真实值，成本数据就是假的。 */
    TokenUsage extractUsage(ChatResponse response, GatewayRequest request) {
        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null && usage.getCompletionTokens() != null
                    && (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0)) {
                return TokenUsage.provider(usage.getPromptTokens(), usage.getCompletionTokens());
            }
        }
        return TokenUsage.estimated(estimateTokens(request.promptText()), 0);
    }

    /**
     * 流式用量：<b>取最后一片的 usage，而不是逐片累加</b>。
     *
     * <p>实测（见 docs/ai-gateway-phase0.md）：DeepSeek 流式响应的中间片 usage 全为 0，
     * 只有最后一片带整段的总用量。逐片累加的结果是 0，成本会整块漏记。
     */
    TokenUsage extractStreamingUsage(ChatResponse lastChunk, String collectedText, GatewayRequest request) {
        if (lastChunk != null && lastChunk.getMetadata() != null) {
            Usage usage = lastChunk.getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null && usage.getCompletionTokens() != null
                    && (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0)) {
                return TokenUsage.provider(usage.getPromptTokens(), usage.getCompletionTokens());
            }
        }
        return TokenUsage.estimated(estimateTokens(request.promptText()), estimateTokens(collectedText));
    }

    /** 字符数 ÷ 3.5，与 ChatMemoryService 的估算口径保持一致。 */
    int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.round(text.length() / 3.5d);
    }

    static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    boolean isRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        return REFUSAL_MARKERS.stream().anyMatch(answer::contains);
    }

    /** 只有"非流式 + 非客户端自带 Key + 无工具 + 有空间维度"的问答才适合进缓存。 */
    private SemanticCache usableCache(GatewayRequest request) {
        if (!properties.getCache().isEnabled() || request.hasClientApiKey() || request.hasTools()) {
            return null;
        }
        if (request.userPrompt() == null || request.userPrompt().isBlank()) {
            return null;
        }
        if (request.scene() != CallScene.KB_QA && request.scene() != CallScene.CHAT_SIMPLE) {
            return null;
        }
        return cacheProvider.getIfAvailable();
    }

    private void tryStoreCache(SemanticCache cache, GatewayRequest request, String modelId, String answer) {
        try {
            cache.store(request.spaceId(), request.userPrompt(), modelId, answer,
                    request.promptKey(), request.promptVersion());
        } catch (Exception e) {
            log.warn("语义缓存写入失败（忽略）：{}", e.toString());
        }
    }

    private void recordUsage(GatewayRequest request, String traceId, String modelId, String routeType,
                             boolean cacheHit, TokenUsage usage, long costMicros, long latencyMs,
                             String outcome, String errorCode) {
        if (!properties.isMeteringEnabled()) {
            return;
        }
        try {
            PricingCalculator.UnitPrice price = pricing.priceOf(modelId);
            LlmUsageRecord record = new LlmUsageRecord();
            record.setTraceId(traceId);
            record.setUserId(request.userId());
            record.setSpaceId(request.spaceId());
            record.setConversationId(request.conversationId());
            record.setScene(request.scene() == null ? "UNKNOWN" : request.scene().name());
            record.setModelId(modelId);
            record.setRouteType(routeType);
            record.setPromptTokens(usage.promptTokens());
            record.setCompletionTokens(usage.completionTokens());
            record.setTotalTokens(usage.total());
            record.setUsageSource(usage.source());
            record.setPromptUnitPrice(price.promptPerMillion());
            record.setCompletionUnitPrice(price.completionPerMillion());
            record.setCostMicros(costMicros);
            record.setLatencyMs(latencyMs);
            record.setCacheHit(cacheHit);
            record.setOutcome(outcome);
            record.setErrorCode(errorCode);
            record.setPromptKey(request.promptKey());
            record.setPromptVersion(request.promptVersion());
            record.setCreatedAt(Instant.now());
            usageRecorder.record(record);
        } catch (Exception e) {
            // 计量永远不能成为"用户拿不到答案"的原因
            log.error("构造用量记录失败（不影响本次回答）：model={}, scene={}", modelId, request.scene(), e);
        }
    }

    private void recordMetrics(String modelId, CallScene scene, String outcome,
                               int promptTokens, int completionTokens, long costMicros,
                               long latencyMs, boolean fallback) {
        if (!properties.isMetricsEnabled()) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        String sceneTag = scene == null ? "UNKNOWN" : scene.name();
        String modelTag = modelId == null ? "none" : modelId;
        try {
            // tag 只用有限枚举（model / scene / outcome），绝不放 userId / spaceId / conversationId，
            // 否则时间序列会随用户数爆炸
            registry.counter("llm_call_total", "model", modelTag, "scene", sceneTag, "outcome", outcome).increment();
            if (promptTokens > 0) {
                registry.counter("llm_token_total", "model", modelTag, "type", "prompt").increment(promptTokens);
            }
            if (completionTokens > 0) {
                registry.counter("llm_token_total", "model", modelTag, "type", "completion")
                        .increment(completionTokens);
            }
            if (costMicros > 0) {
                registry.counter("llm_cost_micros_total", "model", modelTag, "scene", sceneTag).increment(costMicros);
            }
            registry.timer("llm_call_duration", "model", modelTag, "scene", sceneTag)
                    .record(Duration.ofMillis(latencyMs));
            if (fallback && !"none".equals(modelTag)) {
                registry.counter("llm_fallback_total", "from_model", modelTag).increment();
            }
        } catch (Exception e) {
            log.debug("指标上报失败（忽略）：{}", e.toString());
        }
    }

}
