package com.baiyu.agent.gateway;

import java.util.Optional;

/**
 * 语义缓存的抽象。放在网关包里、用接口注入，是为了两个现实原因：
 *
 * <ol>
 *   <li>缓存依赖 {@code EmbeddingModel}，而它只在 {@code VECTOR_STORE_TYPE=qdrant} 时才存在。
 *       默认 {@code memory} 模式下没有这个 Bean，网关必须能在"没有缓存"的情况下正常工作；</li>
 *   <li>L1 阶段先不实现缓存，但网关的调用链已经把它留在了正确的位置上（调用模型之前）。</li>
 * </ol>
 */
public interface SemanticCache {

    Optional<CachedAnswer> lookup(String spaceId, String question, String modelId);

    void store(String spaceId, String question, String modelId, String answer,
               String promptKey, Integer promptVersion);

    /** 缓存条目。key 里带空间与 Prompt 版本，避免跨空间串答案、避免改 Prompt 后命中旧答案。 */
    record CachedAnswer(String answer, String modelId, String promptKey, Integer promptVersion) {
    }
}
