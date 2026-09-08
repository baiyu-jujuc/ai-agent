package com.baiyu.agent.config;

import com.baiyu.agent.config.VectorStoreConfig.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryVectorStoreTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
    }

    @Test
    void addAndSearch() {
        store.add(List.of(new Document("The quick brown fox jumps over the lazy dog", Map.of())));
        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("fox").topK(1).build());
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getText().contains("fox"));
    }

    @Test
    void topKLimit() {
        store.add(List.of(
                new Document("apple fruit", Map.of()),
                new Document("banana fruit", Map.of()),
                new Document("cherry fruit", Map.of())
        ));
        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("fruit").topK(2).build());
        assertEquals(2, results.size());
    }

    @Test
    void emptyStore() {
        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("anything").topK(5).build());
        assertTrue(results.isEmpty());
    }

    @Test
    void chineseQuery() {
        store.add(List.of(new Document("Java 虚拟线程是轻量级并发单元", Map.of())));
        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("虚拟线程").topK(1).build());
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getText().contains("虚拟线程"));
    }

    @Test
    void chineseMixedQuery() {
        store.add(List.of(
                new Document("Spring AI 支持 function calling", Map.of()),
                new Document("Redis 内存数据库缓存方案", Map.of())
        ));
        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("function calling").topK(1).build());
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getText().contains("function calling"));
    }

    @Test
    void metadataPreserved() {
        store.add(List.of(new Document("test content", Map.of("source", "upload", "filename", "test.txt"))));
        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("test").topK(1).build());
        assertFalse(results.isEmpty());
        assertEquals("upload", results.get(0).getMetadata().get("source"));
    }

    @Test
    void deleteById() {
        store.add(List.of(
                new Document("to delete", Map.of()),
                new Document("keep this", Map.of())
        ));
        store.delete(List.of("1"));
        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("delete").topK(10).build());
        assertTrue(results.stream().noneMatch(d -> d.getText().contains("to delete")));
    }

    // B4: similarityThreshold filtering
    @Test
    void similarityThresholdFiltersIrrelevant() {
        store.add(List.of(
                new Document("Java programming language guide", Map.of()),
                new Document("Python data science tutorial", Map.of())
        ));
        // High threshold should filter out unrelated documents
        List<Document> results = store.similaritySearch(
                SearchRequest.builder()
                        .query("Java")
                        .topK(5)
                        .similarityThreshold(0.99)
                        .build());
        assertTrue(results.isEmpty(), "High threshold should filter all results");
    }

    @Test
    void similarityThresholdAllowsRelevant() {
        store.add(List.of(
                new Document("Java programming language guide", Map.of())
        ));
        // Low threshold should allow results
        List<Document> results = store.similaritySearch(
                SearchRequest.builder()
                        .query("Java")
                        .topK(5)
                        .similarityThreshold(0.01)
                        .build());
        assertFalse(results.isEmpty(), "Low threshold should allow relevant results");
    }

    @Test
    void zeroThresholdReturnsAll() {
        store.add(List.of(
                new Document("apple fruit", Map.of()),
                new Document("banana fruit", Map.of()),
                new Document("cherry fruit", Map.of())
        ));
        List<Document> results = store.similaritySearch(
                SearchRequest.builder()
                        .query("fruit")
                        .topK(10)
                        .similarityThreshold(0.0)
                        .build());
        assertEquals(3, results.size(), "Zero threshold should return all results");
    }

    // T8: filter expression support
    @Test
    void filterBySpaceIdEq() {
        store.add(List.of(
                new Document("Java guide", Map.of("space_id", "space-1", "document_id", "doc-1")),
                new Document("Python guide", Map.of("space_id", "space-2", "document_id", "doc-2"))
        ));
        Filter.Expression filter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("space_id"),
                new Filter.Value("space-1"));

        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("guide").topK(10).filterExpression(filter).build());

        assertEquals(1, results.size());
        assertEquals("space-1", results.get(0).getMetadata().get("space_id"));
    }

    @Test
    void filterBySpaceIdNoMatch() {
        store.add(List.of(
                new Document("Java guide", Map.of("space_id", "space-1"))
        ));
        Filter.Expression filter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("space_id"),
                new Filter.Value("space-999"));

        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("guide").topK(10).filterExpression(filter).build());

        assertTrue(results.isEmpty());
    }

    @Test
    void filterAndExpression() {
        store.add(List.of(
                new Document("doc a", Map.of("space_id", "space-1", "version_id", "v1")),
                new Document("doc b", Map.of("space_id", "space-1", "version_id", "v2")),
                new Document("doc c", Map.of("space_id", "space-2", "version_id", "v1"))
        ));
        Filter.Expression spaceFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("space_id"),
                new Filter.Value("space-1"));
        Filter.Expression versionFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("version_id"),
                new Filter.Value("v1"));
        Filter.Expression andFilter = new Filter.Expression(
                Filter.ExpressionType.AND,
                spaceFilter,
                versionFilter);

        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("doc").topK(10).filterExpression(andFilter).build());

        assertEquals(1, results.size());
        assertEquals("space-1", results.get(0).getMetadata().get("space_id"));
        assertEquals("v1", results.get(0).getMetadata().get("version_id"));
    }

    @Test
    void filterOrExpression() {
        store.add(List.of(
                new Document("doc a", Map.of("space_id", "space-1")),
                new Document("doc b", Map.of("space_id", "space-2")),
                new Document("doc c", Map.of("space_id", "space-3"))
        ));
        Filter.Expression f1 = new Filter.Expression(
                Filter.ExpressionType.EQ, new Filter.Key("space_id"), new Filter.Value("space-1"));
        Filter.Expression f2 = new Filter.Expression(
                Filter.ExpressionType.EQ, new Filter.Key("space_id"), new Filter.Value("space-2"));
        Filter.Expression orFilter = new Filter.Expression(Filter.ExpressionType.OR, f1, f2);

        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("doc").topK(10).filterExpression(orFilter).build());

        assertEquals(2, results.size());
    }

    @Test
    void filterNotExpression() {
        store.add(List.of(
                new Document("doc a", Map.of("space_id", "space-1")),
                new Document("doc b", Map.of("space_id", "space-2"))
        ));
        Filter.Expression eq = new Filter.Expression(
                Filter.ExpressionType.EQ, new Filter.Key("space_id"), new Filter.Value("space-1"));
        Filter.Expression notFilter = new Filter.Expression(Filter.ExpressionType.NOT, eq, null);

        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("doc").topK(10).filterExpression(notFilter).build());

        assertEquals(1, results.size());
        assertEquals("space-2", results.get(0).getMetadata().get("space_id"));
    }

    @Test
    void deleteByFilterExpression() {
        store.add(List.of(
                new Document("to delete", Map.of("version_id", "v1")),
                new Document("keep this", Map.of("version_id", "v2"))
        ));
        Filter.Expression filter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("version_id"),
                new Filter.Value("v1"));

        store.delete(filter);

        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("delete").topK(10).build());
        assertTrue(results.stream().noneMatch(d -> d.getText().contains("to delete")));

        List<Document> allResults = store.similaritySearch(
                SearchRequest.builder().query("keep").topK(10).build());
        assertEquals(1, allResults.size());
        assertEquals("v2", allResults.get(0).getMetadata().get("version_id"));
    }

    @Test
    void noFilterReturnsAll() {
        store.add(List.of(
                new Document("doc a", Map.of("space_id", "space-1")),
                new Document("doc b", Map.of("space_id", "space-2"))
        ));
        List<Document> results = store.similaritySearch(
                SearchRequest.builder().query("doc").topK(10).build());
        assertEquals(2, results.size());
    }
}
