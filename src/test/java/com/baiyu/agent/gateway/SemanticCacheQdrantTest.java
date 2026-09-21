package com.baiyu.agent.gateway;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义缓存的<b>运行时</b>验证：真的连一个 Qdrant，真的读写向量库。
 *
 * <pre>
 * # 先在 WSL 里起一个带端口映射的 Qdrant：
 * docker run -d --name qdrant-it -p 6333:6333 -p 6334:6334 docker.m.daocloud.io/qdrant/qdrant:latest
 * # 再跑本测试（默认跳过，因为它依赖外部 Qdrant）：
 * mvn -B test -Dqdrant.it=true -Dtest=SemanticCacheQdrantTest -DfailIfNoTests=false
 * </pre>
 *
 * <p><b>端口提醒</b>：Qdrant 的 REST 在 6333、gRPC 在 6334，而 Spring AI 的 QdrantVectorStore 用的是
 * <b>gRPC 客户端</b>，所以连不上的时候先看端口是不是配成了 6333。
 *
 * <p><b>关于 embedding</b>：本机没有 embedding API Key（DeepSeek 不提供 embedding），
 * 所以这里用<b>确定性替身</b>（字符二元组哈希向量）驱动向量库。它验证的是缓存链路本身：
 * 写入、按相似度召回、空间隔离、模型隔离、TTL、清空。
 * <b>“语义相近但措辞不同”的阈值校准必须用真实 embedding 做</b>，这一条没有在这里伪装成已验证。
 */
@EnabledIfSystemProperty(named = "qdrant.it", matches = "true")
class SemanticCacheQdrantTest {

    private static final String QDRANT_HOST =
            System.getProperty("qdrant.host", "localhost");
    private static final int QDRANT_PORT =
            Integer.parseInt(System.getProperty("qdrant.port", "6334"));   // gRPC 端口；6333 是 REST
    private static final String COLLECTION = "semantic_cache_it";
    private static final String SPACE_A = "space-a";
    private static final String SPACE_B = "space-b";
    private static final String MODEL = "model-primary";

    private static QdrantClient client;
    private static VectorStore cacheStore;

    private GatewayProperties properties;
    private SemanticCacheService cache;

    @BeforeAll
    static void startVectorStore() throws Exception {
        client = new QdrantClient(QdrantGrpcClient.newBuilder(QDRANT_HOST, QDRANT_PORT, false).build());
        try {
            client.deleteCollectionAsync(COLLECTION).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 第一次跑时 collection 不存在，正常
        }
        QdrantVectorStore store = QdrantVectorStore.builder(client, new DeterministicHashEmbeddingModel())
                .collectionName(COLLECTION)
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet();   // 不走 Spring 容器，手动触发建 collection
        cacheStore = store;
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (client != null) {
            try {
                client.deleteCollectionAsync(COLLECTION).get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            client.close();
        }
    }

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.getCache().setCollection(COLLECTION);
        properties.getCache().setSimilarityThreshold(0.92);
        properties.getCache().setTtlHours(24);
        cache = new SemanticCacheService(cacheStore, properties);
    }

    @Test
    void identicalQuestionHitsCacheFromRealQdrant() throws InterruptedException {
        cache.store(SPACE_A, "出发前多少小时申请退改可以免平台服务费？", MODEL, "72 小时及以上", "kb_qa_plain", 1);

        var hit = lookupWithRetry(SPACE_A, "出发前多少小时申请退改可以免平台服务费？");

        assertTrue(hit.isPresent(), "同一个问题第二次问应当命中缓存");
        assertEquals("72 小时及以上", hit.get().answer());
        assertEquals("kb_qa_plain", hit.get().promptKey());
        assertEquals(1, hit.get().promptVersion());
    }

    @Test
    void sameQuestionInAnotherSpaceDoesNotHit() {
        cache.store(SPACE_A, "台风红色预警时客服要做什么？", MODEL, "15 分钟内标记高优先级", "kb_qa_plain", 1);

        var hit = cache.lookup(SPACE_B, "台风红色预警时客服要做什么？", MODEL);

        assertTrue(hit.isEmpty(), "不同知识空间必须隔离：否则就是跨空间越权泄露答案");
    }

    @Test
    void sameQuestionWithAnotherModelDoesNotHit() {
        cache.store(SPACE_A, "备份保留多少天？", MODEL, "45 天", "kb_qa_plain", 1);

        assertTrue(cache.lookup(SPACE_A, "备份保留多少天？", "model-other").isEmpty(),
                "换模型后不能复用旧模型的缓存（成本与效果口径都变了）");
    }

    @Test
    void unrelatedQuestionDoesNotHit() {
        cache.store(SPACE_A, "出发前多少小时申请退改可以免平台服务费？", MODEL, "72 小时及以上", "kb_qa_plain", 1);

        assertTrue(cache.lookup(SPACE_A, "公司员工年假有多少天？", MODEL).isEmpty(),
                "完全无关的问题不应该命中");
    }

    @Test
    void expiredEntryDoesNotHit() {
        properties.getCache().setTtlHours(0);   // 写入即过期
        cache.store(SPACE_A, "这条缓存马上过期", MODEL, "过期答案", "kb_qa_plain", 1);

        assertTrue(cache.lookup(SPACE_A, "这条缓存马上过期", MODEL).isEmpty(), "过期条目要按未命中处理");
    }

    @Test
    void evictSpaceRemovesCachedEntries() throws InterruptedException {
        cache.store(SPACE_A, "要清空的缓存问题", MODEL, "旧答案", "kb_qa_plain", 1);
        assertTrue(lookupWithRetry(SPACE_A, "要清空的缓存问题").isPresent());

        cache.evictSpace(SPACE_A);

        assertTrue(cache.lookup(SPACE_A, "要清空的缓存问题", MODEL).isEmpty(),
                "知识空间内容变更后，该空间的缓存要能被清掉");
    }

    /** 写入后等一小会儿再查：Qdrant 的 upsert 是异步的，测试里毫秒级重查可能看不到。 */
    private java.util.Optional<SemanticCache.CachedAnswer> lookupWithRetry(String spaceId, String question)
            throws InterruptedException {
        for (int attempt = 1; attempt <= 10; attempt++) {
            var hit = cache.lookup(spaceId, question, MODEL);
            if (hit.isPresent()) {
                return hit;
            }
            Thread.sleep(500);
        }
        printDiagnostics(spaceId, question);
        return java.util.Optional.empty();
    }

    /** 命中不了的时候，用阈值 0 再搜一次，把真实分数和 payload 打出来。 */
    private void printDiagnostics(String spaceId, String question) {
        System.out.println("[cache-it] 命中失败，开始诊断：space=" + spaceId + ", question=" + question);
        var raw = cacheStore.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder()
                .query(question)
                .topK(3)
                .similarityThreshold(0.0)
                .build());
        System.out.println("[cache-it] 无阈值检索返回 " + raw.size() + " 条");
        raw.forEach(document -> System.out.println("[cache-it]   score=" + document.getScore()
                + " meta=" + document.getMetadata()));
    }

    @Test
    void cacheUsesItsOwnCollectionNotTheKnowledgeBaseOne() {
        String actual = (String) ReflectionTestUtils.getField(cacheStore, "collectionName");
        assertEquals(COLLECTION, actual);
        assertNotEquals("kb_chunks", actual, "缓存绝不能写进知识库的 collection");
    }

    /** 确定性替身 embedding：字符二元组哈希到固定维度并做 L2 归一化。 */
    static final class DeterministicHashEmbeddingModel implements EmbeddingModel {

        private static final int DIMENSIONS = 64;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> instructions = request.getInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(vector(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        static float[] vector(String text) {
            float[] vector = new float[DIMENSIONS];
            String normalized = text == null ? "" : text.replaceAll("\\s+", "");
            for (int i = 0; i < normalized.length(); i++) {
                String bigram = normalized.substring(i, Math.min(i + 2, normalized.length()));
                vector[Math.floorMod(bigram.hashCode(), DIMENSIONS)] += 1f;
            }
            double norm = 0;
            for (float value : vector) {
                norm += value * value;
            }
            if (norm > 0) {
                float scale = (float) (1 / Math.sqrt(norm));
                for (int i = 0; i < vector.length; i++) {
                    vector[i] *= scale;
                }
            }
            return vector;
        }
    }
}
