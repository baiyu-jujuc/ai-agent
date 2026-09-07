package com.baiyu.agent.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RagServiceTest {

    private RagService ragService;
    private VectorStore vectorStore;
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        chatClient = mock(ChatClient.class);
        ragService = new RagService(chatClient, vectorStore);
        org.springframework.test.util.ReflectionTestUtils.setField(ragService, "vectorStoreType", "memory");
    }

    @Test
    void addDocument() {
        doNothing().when(vectorStore).add(anyList());
        String result = ragService.addDocument("test content", "metadata");
        assertTrue(result.contains("chunk"), result);
        verify(vectorStore).add(anyList());
    }

    @Test
    void addTextFile() {
        doNothing().when(vectorStore).add(anyList());
        String result = ragService.addTextFile("test.txt", "hello world content");
        assertTrue(result.contains("test.txt"), result);
        verify(vectorStore).add(anyList());
    }

    @Test
    void uploadAndIndexTxtFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "hello world".getBytes());
        doNothing().when(vectorStore).add(anyList());
        String result = ragService.uploadAndIndex(file);
        assertTrue(result.contains("test.txt"), result);
        verify(vectorStore).add(anyList());
    }

    @Test
    void uploadAndIndexMarkdownFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.md", "text/markdown", "# Title\nHello".getBytes());
        doNothing().when(vectorStore).add(anyList());
        String result = ragService.uploadAndIndex(file);
        assertTrue(result.contains("test.md"), result);
    }

    @Test
    void uploadEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);
        String result = ragService.uploadAndIndex(file);
        assertTrue(result.contains("empty"), result.toLowerCase());
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void searchReturnsResults() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("result text", java.util.Map.of())));
        List<String> results = ragService.search("query", 5);
        assertFalse(results.isEmpty());
        assertEquals("result text", results.get(0));
    }

    @Test
    void searchEmptyStore() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("store empty"));
        List<String> results = ragService.search("query", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void getVectorStoreType() {
        assertEquals("memory", ragService.getVectorStoreType());
    }

    @Test
    void getStoreTypeName() {
        assertEquals("InMemory", ragService.getStoreTypeName());
    }
}
