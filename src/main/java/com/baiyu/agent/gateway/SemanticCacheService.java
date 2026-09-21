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
            // 注意两点（都是真实 Qdrant 上踩过的坑）：
            //   1) metadata 里不能放 null —— QdrantValueFactory 遇到不认识的类型（包括 null）会直接抛异常；
            //   2) 只支持 String / Integer / Double / Float / Boolean / Map / List，**Long 不支持**，
            //      所以过期时间用 epoch 秒（Integer），不要用毫秒（Long）。
            if (modelId != null) {
                metadata.put(META_MODEL_ID, modelId);
            }
            if (promptKey != null) {
                metadata.put(META_PROMPT_KEY, promptKey);
            }
            if (promptVersion != null) {
                metadata.put(META_PROMPT_VERSION, promptVersion);
            }
            metadata.put(META_ANSWER, answer);
            metadata.put(META_CACHE_KEY, cacheKey(spaceId, modelId, promptKey, promptVersion, question));
            metadata.put(META_EXPIRES_AT, expiresAtEpochSecond());

            // 注意：Qdrant 的 point id 必须是 UUID 或整数，不能直接放 64 位 sha256 字符串
            // （真实环境里会报 "UUID string too large"，而且写入失败会被上面的 catch 吞掉，很难发现）。
            // 这里用 sha256 派生一个确定性 UUID：同样的问题 → 同样的 id → 重复写入是覆盖，不会膨胀。
            Document document = new Document(
                    pointId(cacheKey(spaceId, modelId, promptKey, promptVersion, question)), question, metadata);
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

    /** 把缓存 key 转成 Qdrant 能接受的确定性 UUID。 */
    static String pointId(String cacheKey) {
        return java.util.UUID.nameUUIDFromBytes(cacheKey.getBytes(StandardCharsets.UTF_8)).toString();
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
            // 到达过期时刻即视为过期（>= 而不是 >）：
            // 否则 TTL=0 的条目会在同一秒内被视为有效，语义上说不通
            return Instant.now().getEpochSecond() >= number.longValue();
        }
        return false;
    }

    /** 过期时间（epoch 秒）。用 Integer 是因为 Qdrant payload 不支持 Long；够用到 2038 年。 */
    private int expiresAtEpochSecond() {
        return (int) Instant.now().plusSeconds(properties.getCache().getTtlHours() * 3600L).getEpochSecond();
    }

    GatewayProperties properties() {
        return properties;
    }
}
