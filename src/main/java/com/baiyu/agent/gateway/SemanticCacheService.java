package com.baiyu.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存：把"问题 → 答案"存进<b>独立</b>的向量 collection。
 *
 * <p>三件事必须同时成立，缺一条都会出问题：
 * <ol>
 *   <li><b>独立 collection</b>（{@code semantic_cache}）：缓存和知识库 chunk 的生命周期完全不同，
 *       混在一起会污染检索结果；</li>
 *   <li><b>key 里带空间 / 模型 / Prompt 版本</b>：否则改 Prompt、换模型之后还会命中旧答案；</li>
 *   <li><b>检索时按 space_id 过滤</b>：不同空间的数据必须隔离，这是越权问题，不是体验问题。</li>
 * </ol>
 *
 * <p>只有在 {@code VECTOR_STORE_TYPE=qdrant}（此时才有 EmbeddingModel）时才装配；
 * memory 模式下这个 Bean 不存在，网关照常工作，只是不命中缓存。
 */
@Service
@ConditionalOnProperty(name = "agent.storage.vector-store", havingValue = "qdrant")
public class SemanticCacheService implements SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    static final String META_SPACE_ID = "space_id";
    static final String META_MODEL_ID = "model_id";
    static final String META_PROMPT_KEY = "prompt_key";
    static final String META_PROMPT_VERSION = "prompt_version";
    static final String META_CACHE_KEY = "cache_key";
    static final String META_ANSWER = "answer";
    static final String META_EXPIRES_AT = "expires_at";

    private final VectorStore cacheVectorStore;
    private final GatewayProperties properties;

    public SemanticCacheService(@Qualifier("semanticCacheVectorStore") VectorStore cacheVectorStore,
                                GatewayProperties properties) {
        this.cacheVectorStore = cacheVectorStore;
        this.properties = properties;
        log.info("语义缓存已启用：collection={}，相似度阈值={}，TTL={}小时",
                properties.getCache().getCollection(),
                properties.getCache().getSimilarityThreshold(),
                properties.getCache().getTtlHours());
    }

    @Override
    public Optional<CachedAnswer> lookup(String spaceId, String question, String modelId) {
        if (!properties.getCache().isEnabled() || spaceId == null || question == null || question.isBlank()) {
            return Optional.empty();
        }
        double threshold = properties.getCache().getSimilarityThreshold();
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(question)
                    .topK(1)
                    .similarityThreshold(threshold)
                    .filterExpression(spaceFilter(spaceId, modelId))
                    .build();
            List<Document> hits = cacheVectorStore.similaritySearch(request);
            if (hits == null || hits.isEmpty()) {
                return Optional.empty();
            }
            Document top = hits.get(0);
            Double score = top.getScore();
            if (score != null && score < threshold) {
                log.debug("缓存相似度不足（{} < {}），按未命中处理", score, threshold);
                return Optional.empty();
            }
            if (isExpired(top)) {
                log.debug("缓存条目已过期，按未命中处理：{}", top.getId());
                return Optional.empty();
            }
            Object answer = top.getMetadata().get(META_ANSWER);
            if (answer == null || answer.toString().isBlank()) {
                return Optional.empty();
            }
            Object promptKey = top.getMetadata().get(META_PROMPT_KEY);
            Object promptVersion = top.getMetadata().get(META_PROMPT_VERSION);
            log.info("语义缓存命中：space={}, model={}, score={}", spaceId, modelId, score);
            return Optional.of(new CachedAnswer(answer.toString(),
                    modelId,
                    promptKey == null ? null : promptKey.toString(),
                    promptVersion instanceof Number number ? number.intValue() : null));
        } catch (Exception e) {
            // 缓存是加速手段，坏了不能让正常问答跟着坏
            log.warn("语义缓存查询失败（按未命中处理）：{}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void store(String spaceId, String question, String modelId, String answer,
                      String promptKey, Integer promptVersion) {
        if (!properties.getCache().isEnabled() || spaceId == null || question == null || answer == null) {
            return;
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(META_SPACE_ID, spaceId);
            metadata.put(META_MODEL_ID, modelId);
            metadata.put(META_PROMPT_KEY, promptKey);
            metadata.put(META_PROMPT_VERSION, promptVersion);
            metadata.put(META_ANSWER, answer);
            metadata.put(META_CACHE_KEY, cacheKey(spaceId, modelId, promptKey, promptVersion, question));
            metadata.put(META_EXPIRES_AT,
                    Instant.now().plusSeconds(properties.getCache().getTtlHours() * 3600L).toEpochMilli());

            Document document = new Document(
                    cacheKey(spaceId, modelId, promptKey, promptVersion, question), question, metadata);
            cacheVectorStore.add(List.of(document));
        } catch (Exception e) {
            log.warn("语义缓存写入失败（忽略）：{}", e.toString());
        }
    }

    /** 知识空间内容变更时主动清缓存（例如上传了文档、回滚了版本）。 */
    public void evictSpace(String spaceId) {
        try {
            cacheVectorStore.delete(new Filter.Expression(Filter.ExpressionType.EQ,
                    new Filter.Key(META_SPACE_ID), new Filter.Value(spaceId)));
            log.info("已清空知识空间 {} 的语义缓存", spaceId);
        } catch (Exception e) {
            log.warn("清空语义缓存失败：{}", e.toString());
        }
    }

    /**
     * 缓存 key：空间 + 模型 + Prompt key/版本 + 归一化后的问题。
     * 用 sha256 是为了让"相同问题"在向量库里有稳定的 id（重复写入即覆盖，不会无限膨胀）。
     */
    public String cacheKey(String spaceId, String modelId, String promptKey, Integer promptVersion, String question) {
        String normalized = question == null ? "" : question.trim().replaceAll("\\s+", " ");
        String raw = spaceId + "|" + modelId + "|" + promptKey + "|" + promptVersion + "|" + normalized;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Filter.Expression spaceFilter(String spaceId, String modelId) {
        Filter.Expression bySpace = new Filter.Expression(Filter.ExpressionType.EQ,
                new Filter.Key(META_SPACE_ID), new Filter.Value(spaceId));
        if (modelId == null || modelId.isBlank()) {
            return bySpace;
        }
        Filter.Expression byModel = new Filter.Expression(Filter.ExpressionType.EQ,
                new Filter.Key(META_MODEL_ID), new Filter.Value(modelId));
        return new Filter.Expression(Filter.ExpressionType.AND, bySpace, byModel);
    }

    private boolean isExpired(Document document) {
        Object expiresAt = document.getMetadata().get(META_EXPIRES_AT);
        if (expiresAt instanceof Number number) {
            return Instant.now().toEpochMilli() > number.longValue();
        }
        return false;
    }

    GatewayProperties properties() {
        return properties;
    }
}
