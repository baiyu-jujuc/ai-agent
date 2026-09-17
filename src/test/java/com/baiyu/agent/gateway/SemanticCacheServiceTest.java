package com.baiyu.agent.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义缓存的单元测试（用 mock 的 VectorStore，不需要真的起 Qdrant）：
 * 命中判定、空间隔离、TTL、独立 key、失败不影响主链路。
 */
class SemanticCacheServiceTest {

    private static final String SPACE = "space-1";
    private static final String MODEL = "model-primary";

    private VectorStore cacheVectorStore;
    private GatewayProperties properties;
    private SemanticCacheService service;

    @BeforeEach
    void setUp() {
        cacheVectorStore = mock(VectorStore.class);
        properties = new GatewayProperties();
        properties.getCache().setSimilarityThreshold(0.92);
        properties.getCache().setTtlHours(24);
        service = new SemanticCacheService(cacheVectorStore, properties);
    }

    @Test
    void lookupReturnsCachedAnswerWhenSimilarityIsAboveThreshold() {
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(cachedDocument("缓存的答案", "kb_qa_plain", 1, futureExpiry(), 0.95)));

        Optional<SemanticCache.CachedAnswer> hit = service.lookup(SPACE, "问题", MODEL);

        assertTrue(hit.isPresent());
        assertEquals("缓存的答案", hit.get().answer());
        assertEquals("kb_qa_plain", hit.get().promptKey());
        assertEquals(1, hit.get().promptVersion());
    }

    @Test
    void lookupReturnsEmptyWhenSimilarityIsBelowThreshold() {
        // 即使向量库因为实现差异返回了低分结果，这里也要再兜一道
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(cachedDocument("不该命中的答案", "k", 1, futureExpiry(), 0.80)));

        assertTrue(service.lookup(SPACE, "问题", MODEL).isEmpty());
    }

    @Test
    void lookupReturnsEmptyForExpiredEntry() {
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(cachedDocument("过期答案", "k", 1,
                        Instant.now().minusSeconds(60).toEpochMilli(), 0.99)));

        assertTrue(service.lookup(SPACE, "问题", MODEL).isEmpty());
    }

    @Test
    void lookupFiltersBySpaceAndModel() {
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.lookup(SPACE, "问题", MODEL);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(cacheVectorStore).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();
        assertEquals(0.92, request.getSimilarityThreshold());
        assertEquals(1, request.getTopK());
        assertNotNull(request.getFilterExpression(), "必须带过滤条件，否则会跨空间命中");

        Map<String, String> equalityFilters = new HashMap<>();
        collectEqualityFilters(request.getFilterExpression(), equalityFilters);
        assertEquals(SPACE, equalityFilters.get(SemanticCacheService.META_SPACE_ID));
        assertEquals(MODEL, equalityFilters.get(SemanticCacheService.META_MODEL_ID));
    }

    @Test
    void storeWritesKeyTtlAndMetadata() {
        service.store(SPACE, "问题", MODEL, "答案", "kb_qa_plain", 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(cacheVectorStore).add(captor.capture());
        Document document = captor.getValue().get(0);

        assertEquals(SPACE, document.getMetadata().get(SemanticCacheService.META_SPACE_ID));
        assertEquals(MODEL, document.getMetadata().get(SemanticCacheService.META_MODEL_ID));
        assertEquals("kb_qa_plain", document.getMetadata().get(SemanticCacheService.META_PROMPT_KEY));
        assertEquals(2, document.getMetadata().get(SemanticCacheService.META_PROMPT_VERSION));
        assertEquals("答案", document.getMetadata().get(SemanticCacheService.META_ANSWER));
        assertEquals(service.cacheKey(SPACE, MODEL, "kb_qa_plain", 2, "问题"), document.getId());

        long expiresAt = ((Number) document.getMetadata().get(SemanticCacheService.META_EXPIRES_AT)).longValue();
        assertTrue(expiresAt > Instant.now().toEpochMilli(), "TTL 必须落在未来");
    }

    @Test
    void cacheKeyIsStableNormalizedAndSpaceScoped() {
        String key = service.cacheKey(SPACE, MODEL, "k", 1, "  今天   天气如何  ");

        assertEquals(key, service.cacheKey(SPACE, MODEL, "k", 1, "今天 天气如何"),
                "空白差异不应该造成缓存穿透");
        assertNotEquals(key, service.cacheKey("space-2", MODEL, "k", 1, "今天 天气如何"),
                "不同空间必须是不同的 key");
        assertNotEquals(key, service.cacheKey(SPACE, MODEL, "k", 2, "今天 天气如何"),
                "Prompt 版本变了就不能再命中旧缓存");
    }

    @Test
    void evictSpaceDeletesEntriesBySpaceFilter() {
        service.evictSpace(SPACE);

        ArgumentCaptor<Filter.Expression> captor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(cacheVectorStore).delete(captor.capture());
        Map<String, String> filters = new HashMap<>();
        collectEqualityFilters(captor.getValue(), filters);
        assertEquals(SPACE, filters.get(SemanticCacheService.META_SPACE_ID));
    }

    @Test
    void everythingSkippedWhenCacheDisabled() {
        properties.getCache().setEnabled(false);

        assertTrue(service.lookup(SPACE, "问题", MODEL).isEmpty());
        service.store(SPACE, "问题", MODEL, "答案", "k", 1);

        verify(cacheVectorStore, never()).similaritySearch(any(SearchRequest.class));
        verify(cacheVectorStore, never()).add(anyList());
    }

    @Test
    void storeFailureIsSwallowed() {
        doThrow(new RuntimeException("qdrant down")).when(cacheVectorStore).add(anyList());

        service.store(SPACE, "问题", MODEL, "答案", "k", 1);   // 不抛异常即通过
        verify(cacheVectorStore).add(anyList());
    }

    @Test
    void lookupFailureIsSwallowed() {
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("qdrant timeout"));

        assertTrue(service.lookup(SPACE, "问题", MODEL).isEmpty(),
                "缓存坏了要按未命中处理，而不是把问答接口带崩");
    }

    private Document cachedDocument(String answer, String promptKey, Integer version,
                                    long expiresAt, double score) {
        // expiresAt 用 epoch millis 传入：futureExpiry() 表示"还没过期"
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SemanticCacheService.META_SPACE_ID, SPACE);
        metadata.put(SemanticCacheService.META_MODEL_ID, MODEL);
        metadata.put(SemanticCacheService.META_PROMPT_KEY, promptKey);
        metadata.put(SemanticCacheService.META_PROMPT_VERSION, version);
        metadata.put(SemanticCacheService.META_ANSWER, answer);
        metadata.put(SemanticCacheService.META_EXPIRES_AT, expiresAt);
        return Document.builder().id("doc-1").text("问题").metadata(metadata).score(score).build();
    }

    private long futureExpiry() {
        return Instant.now().plusSeconds(3600).toEpochMilli();
    }

    private void collectEqualityFilters(Filter.Operand operand, Map<String, String> target) {
        if (operand instanceof Filter.Expression expression) {
            if (expression.type() == Filter.ExpressionType.EQ
                    && expression.left() instanceof Filter.Key key
                    && expression.right() instanceof Filter.Value value) {
                target.put(key.key(), String.valueOf(value.value()));
                return;
            }
            collectEqualityFilters(expression.left(), target);
            collectEqualityFilters(expression.right(), target);
        }
    }
}
