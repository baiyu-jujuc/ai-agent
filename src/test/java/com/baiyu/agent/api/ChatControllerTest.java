package com.baiyu.agent.api;

import com.baiyu.agent.agent.Agent;
import com.baiyu.agent.agent.CoordinatorAgent;
import com.baiyu.agent.config.ChatModelFactory;
import com.baiyu.agent.config.GlobalExceptionHandler;
import com.baiyu.agent.config.ModelRegistry;
import com.baiyu.agent.memory.ChatMemoryService;
import com.baiyu.agent.orchestrator.OrchestrationStrategy;
import com.baiyu.agent.rag.RagService;
import com.baiyu.agent.tool.FunctionCallingService;
import com.baiyu.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {

    private MockMvc mockMvc;
    private ChatMemoryService memoryService;

    @BeforeEach
    void setUp() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatClient chatClient = mock(ChatClient.class);
        CoordinatorAgent coordinatorAgent = mock(CoordinatorAgent.class);
        Map<String, Agent> agents = new HashMap<>();
        memoryService = mock(ChatMemoryService.class);
        ToolRegistry toolRegistry = new ToolRegistry(Collections.emptyList());
        FunctionCallingService functionCallingService = mock(FunctionCallingService.class);
        RagService ragService = mock(RagService.class);
        Map<String, OrchestrationStrategy> strategies = new HashMap<>();

        ModelRegistry modelRegistry = new ModelRegistry(
                "deepseek-v4-flash", "deepseek-v4-pro",
                "deepseek-v4-flash", "deepseek-v4-flash-vision-exp");

        when(memoryService.getHistory(anyString())).thenReturn(Collections.emptyList());
        when(memoryService.getStorageType()).thenReturn("memory");
        when(memoryService.getConversationIds()).thenReturn(Collections.emptySet());
        when(ragService.getVectorStoreType()).thenReturn("memory");

        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);

        ChatController controller = new ChatController(
                chatModel, chatClient, coordinatorAgent, agents,
                memoryService, toolRegistry, functionCallingService,
                ragService, strategies, modelRegistry, chatModelFactory, false);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void emptyMessageReturns400() throws Exception {
        mockMvc.perform(post("/api/chat/simple")
                        .contentType("application/json")
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nullMessageReturns400() throws Exception {
        mockMvc.perform(post("/api/chat/simple")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overLengthMessageReturns400() throws Exception {
        String longMsg = "A".repeat(10001);
        mockMvc.perform(post("/api/chat/simple")
                        .contentType("application/json")
                        .content("{\"message\":\"" + longMsg + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownStrategyReturnsError() throws Exception {
        mockMvc.perform(post("/api/chat/orchestrate")
                        .contentType("application/json")
                        .content("{\"message\":\"test\",\"strategy\":\"nonexistent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getModelsReturnsList() throws Exception {
        mockMvc.perform(get("/api/chat/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists());
    }

    @Test
    void getStorageStatus() throws Exception {
        mockMvc.perform(get("/api/chat/storage-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryBackend").exists())
                .andExpect(jsonPath("$.vectorStoreBackend").exists());
    }

    @Test
    void getStrategiesEmptyByDefault() throws Exception {
        mockMvc.perform(get("/api/chat/strategies"))
                .andExpect(status().isOk());
    }

    @Test
    void getToolsEmptyByDefault() throws Exception {
        mockMvc.perform(get("/api/chat/tools"))
                .andExpect(status().isOk());
    }

    @Test
    void getHistoryEmptyByDefault() throws Exception {
        mockMvc.perform(get("/api/chat/history/default"))
                .andExpect(status().isOk());
    }
}
