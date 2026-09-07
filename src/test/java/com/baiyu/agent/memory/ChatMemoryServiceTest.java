package com.baiyu.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChatMemoryServiceTest {

    private ChatMemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new ChatMemoryService(null);
        org.springframework.test.util.ReflectionTestUtils.setField(memoryService, "memoryType", "memory");
        org.springframework.test.util.ReflectionTestUtils.setField(memoryService, "maxTokens", 8000);
        org.springframework.test.util.ReflectionTestUtils.setField(memoryService, "maxMessages", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(memoryService, "maxConversations", 3);
    }

    @Test
    void addAndRetrieveMessages() {
        memoryService.addUserMessage("conv1", "Hello");
        memoryService.addAssistantMessage("conv1", "Hi there");
        List<?> history = memoryService.getHistory("conv1");
        assertEquals(2, history.size());
    }

    @Test
    void messageCountTrimmed() {
        for (int i = 0; i < 10; i++) {
            memoryService.addUserMessage("conv1", "msg" + i);
            memoryService.addAssistantMessage("conv1", "reply" + i);
        }
        List<?> history = memoryService.getHistory("conv1");
        assertTrue(history.size() <= 5, "Expected <= 5 messages, got " + history.size());
    }

    @Test
    void conversationEviction() {
        for (int i = 0; i < 5; i++) {
            memoryService.addUserMessage("conv" + i, "test");
        }
        Set<String> ids = memoryService.getConversationIds();
        assertTrue(ids.size() <= 3, "Expected <= 3 conversations, got " + ids.size());
    }

    @Test
    void clearHistory() {
        memoryService.addUserMessage("conv1", "Hello");
        memoryService.clearHistory("conv1");
        List<?> history = memoryService.getHistory("conv1");
        assertTrue(history.isEmpty());
    }

    @Test
    void nonExistentConversation() {
        List<?> history = memoryService.getHistory("nonexistent");
        assertTrue(history.isEmpty());
    }

    @Test
    void storageTypeIsMemory() {
        assertEquals("memory", memoryService.getStorageType());
    }

    @Test
    void separateConversations() {
        memoryService.addUserMessage("conv1", "Hello 1");
        memoryService.addUserMessage("conv2", "Hello 2");
        assertEquals(1, memoryService.getHistory("conv1").size());
        assertEquals(1, memoryService.getHistory("conv2").size());
    }
}
