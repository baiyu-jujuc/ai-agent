package com.baiyu.agent.api;

import com.baiyu.agent.agent.Agent;
import com.baiyu.agent.agent.CoordinatorAgent;
import com.baiyu.agent.memory.ChatMemoryService;
import com.baiyu.agent.orchestrator.OrchestrationStrategy;
import com.baiyu.agent.rag.RagService;
import com.baiyu.agent.tool.FunctionCallingService;
import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final CoordinatorAgent coordinatorAgent;
    private final Map<String, Agent> agents;
    private final ChatMemoryService memoryService;
    private final List<ToolComponent> toolComponents;
    private final FunctionCallingService functionCallingService;
    private final RagService ragService;
    private final Map<String, OrchestrationStrategy> strategies;

    public ChatController(ChatModel chatModel, ChatClient chatClient,
                         CoordinatorAgent coordinatorAgent,
                         Map<String, Agent> agents,
                         ChatMemoryService memoryService,
                         List<ToolComponent> toolComponents,
                         FunctionCallingService functionCallingService,
                         RagService ragService,
                         Map<String, OrchestrationStrategy> strategies) {
        this.chatModel = chatModel;
        this.chatClient = chatClient;
        this.coordinatorAgent = coordinatorAgent;
        this.agents = agents;
        this.memoryService = memoryService;
        this.toolComponents = toolComponents;
        this.functionCallingService = functionCallingService;
        this.ragService = ragService;
        this.strategies = strategies;
    }

    private static final int MAX_MESSAGE_LENGTH = 10000;

    @PostMapping("/simple")
    public Map<String, Object> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message 超过最大长度限制 (" + MAX_MESSAGE_LENGTH + " 字符)");
        }
        String conversationId = request.getOrDefault("conversationId", "default");
        String model = request.getOrDefault("model", "deepseek-v4-flash");
        boolean useTools = Boolean.parseBoolean(request.getOrDefault("useTools", "false"));

        List<Message> history = memoryService.getHistory(conversationId);
        memoryService.addUserMessage(conversationId, message);

        String response;
        try {
            if (useTools) {
                response = functionCallingService.executeWithTools(message, model, history);
            } else {
                response = coordinatorAgent.execute(message, history);
            }
            if (response == null || response.isBlank()) {
                response = "AI 返回了空回复，请重试。";
            }
        } catch (Exception e) {
            response = "请求失败: " + e.getClass().getSimpleName() + " — " +
                    (e.getMessage() != null ? e.getMessage() : "未知错误");
        }
        memoryService.addAssistantMessage(conversationId, response);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("response", response);
        result.put("conversationId", conversationId);
        result.put("model", model);
        result.put("toolsUsed", useTools);
        return result;
    }

    @GetMapping(value = "/stream")
    public Flux<String> streamChat(@RequestParam String message,
                                   @RequestParam(defaultValue = "default") String conversationId,
                                   @RequestParam(defaultValue = "deepseek-v4-flash") String model) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message 超过最大长度限制");
        }
        List<Message> history = memoryService.getHistory(conversationId);
        memoryService.addUserMessage(conversationId, message);

        StringBuilder reply = new StringBuilder();
        return chatClient.prompt()
                .messages(history)
                .user(message)
                .options(ChatOptions.builder().model(model).build())
                .stream()
                .content()
                .doOnNext(reply::append)
                .doOnComplete(() -> memoryService.addAssistantMessage(conversationId, reply.toString()))
                .doOnError(e -> memoryService.addAssistantMessage(conversationId, "[stream error] " + e.getMessage()));
    }

    @PostMapping("/agent/{agentName}")
    public Map<String, Object> chatWithAgent(@PathVariable String agentName,
                                             @RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message 超过最大长度限制");
        }
        String conversationId = request.getOrDefault("conversationId", "default");
        String model = request.getOrDefault("model", "deepseek-v4-flash");

        List<Message> history = memoryService.getHistory(conversationId);
        memoryService.addUserMessage(conversationId, message);

        Agent targetAgent = agents.get(agentName);
        if (targetAgent == null) {
            targetAgent = coordinatorAgent;
        }

        String response;
        try {
            response = targetAgent.executeWithModel(message, model, history);
        } catch (Exception e) {
            response = "Agent 执行失败: " + e.getMessage();
        }
        memoryService.addAssistantMessage(conversationId, response);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("response", response);
        result.put("agent", targetAgent.getName());
        result.put("conversationId", conversationId);
        result.put("model", model);
        return result;
    }

    @PostMapping("/orchestrate")
    public Map<String, Object> orchestrate(@RequestBody Map<String, Object> request) {
        String input = (String) request.get("message");
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (input.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message 超过最大长度限制 (" + MAX_MESSAGE_LENGTH + " 字符)");
        }
        String strategyName = (String) request.getOrDefault("strategy", "sequential");
        @SuppressWarnings("unchecked")
        List<String> agentNames = (List<String>) request.getOrDefault("agents", List.of("code", "research"));
        String conversationId = (String) request.getOrDefault("conversationId", "default");

        OrchestrationStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            return Map.of("error", "Unknown strategy: " + strategyName + ". Available: " + strategies.keySet());
        }

        List<Message> history = memoryService.getHistory(conversationId);
        memoryService.addUserMessage(conversationId, input);
        Map<String, String> results = strategy.execute(input, history, agentNames);

        String combined = results.values().stream()
                .reduce((a, b) -> a + "\n\n---\n\n" + b)
                .orElse("No results");
        memoryService.addAssistantMessage(conversationId, combined);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategyName);
        result.put("agentResults", results);
        result.put("conversationId", conversationId);
        return result;
    }

    @GetMapping("/strategies")
    public List<Map<String, String>> getStrategies() {
        return strategies.values().stream()
                .map(s -> Map.of("name", s.getName()))
                .toList();
    }

    @GetMapping("/models")
    public List<Map<String, String>> getModels() {
        List<Map<String, String>> models = new ArrayList<>();
        models.add(Map.of("id", "deepseek-v4-flash", "name", "DeepSeek V4 Flash (低成本)", "description", "快速响应，适合日常对话"));
        models.add(Map.of("id", "deepseek-v4-pro", "name", "DeepSeek V4 Pro (高性能)", "description", "最强推理能力，适合复杂任务"));
        models.add(Map.of("id", "deepseek-v4-flash-vision-exp", "name", "DeepSeek V4 Vision", "description", "支持图像输入(实验)"));
        return models;
    }

    @GetMapping("/tools")
    public List<Map<String, String>> getTools() {
        List<Map<String, String>> result = new ArrayList<>();
        for (Object tool : toolComponents) {
            for (var method : tool.getClass().getDeclaredMethods()) {
                Tool t = method.getAnnotation(Tool.class);
                if (t != null) {
                    String name = t.name().isEmpty() ? method.getName() : t.name();
                    result.add(Map.of("name", name, "description", t.description()));
                }
            }
        }
        return result;
    }

    @GetMapping("/history/{conversationId}")
    public List<Map<String, String>> getHistory(@PathVariable String conversationId) {
        List<Message> history = memoryService.getHistory(conversationId);
        List<Map<String, String>> result = new ArrayList<>();
        for (Message msg : history) {
            result.add(Map.of(
                    "role", msg.getMessageType().name(),
                    "content", msg.getText()
            ));
        }
        return result;
    }

    @DeleteMapping("/history/{conversationId}")
    public Map<String, String> clearHistory(@PathVariable String conversationId) {
        memoryService.clearHistory(conversationId);
        return Map.of("status", "cleared", "conversationId", conversationId);
    }

    @GetMapping("/storage-status")
    public Map<String, Object> getStorageStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("memoryBackend", memoryService.getStorageType());
        status.put("vectorStoreBackend", ragService.getVectorStoreType());
        status.put("conversations", memoryService.getConversationIds().size());
        status.put("timestamp", java.time.Instant.now().toString());
        return status;
    }

}
