package com.baiyu.agent.config;

import com.baiyu.agent.config.VectorStoreConfig.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

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
}
