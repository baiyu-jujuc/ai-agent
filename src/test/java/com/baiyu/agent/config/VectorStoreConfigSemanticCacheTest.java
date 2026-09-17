package com.baiyu.agent.config;

import com.baiyu.agent.gateway.GatewayProperties;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * "缓存绝不能复用知识库的向量 collection"这条要求的可执行证据：
 * 直接构造 Bean，读出它内部真实使用的 collection 名来比对。
 */
class VectorStoreConfigSemanticCacheTest {

    private final VectorStoreConfig config = new VectorStoreConfig();
    private final QdrantClient qdrantClient = mock(QdrantClient.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    @Test
    void semanticCacheUsesItsOwnCollection() {
        GatewayProperties properties = new GatewayProperties();

        VectorStore store = config.semanticCacheVectorStore(embeddingModel, qdrantClient, properties, "kb_chunks");

        assertInstanceOf(QdrantVectorStore.class, store);
        String collectionName = (String) ReflectionTestUtils.getField(store, "collectionName");
        assertEquals("semantic_cache", collectionName);
        assertNotEquals("kb_chunks", collectionName, "缓存不能写进知识库的 collection");
    }

    @Test
    void refusesToShareCollectionWithKnowledgeBase() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCache().setCollection("kb_chunks");

        assertThrows(IllegalStateException.class,
                () -> config.semanticCacheVectorStore(embeddingModel, qdrantClient, properties, "kb_chunks"));
    }
}
