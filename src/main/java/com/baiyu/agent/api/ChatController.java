package com.baiyu.agent.api;

import com.baiyu.agent.agent.Agent;
import com.baiyu.agent.agent.CoordinatorAgent;
import com.baiyu.agent.memory.ChatMemoryService;
import com.baiyu.agent.tool.FunctionCallingService;
import com.baiyu.agent.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
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
    private final ToolRegistry toolRegistry;
    private final FunctionCallingService functionCallingService;

    private static final List<String> AVAILABLE_MODELS = List.of(
            "deepseek-v4-flash",
            "deepseek-v4-pro",
            "deepseek-v4-flash-vision-exp"
    );

    public ChatController(ChatModel chatModel, ChatClient chatClient,
                         CoordinatorAgent coordinatorAgent,
                         Map<String, Agent> agents,
                         ChatMemoryService memoryService,
                         ToolRegistry toolRegistry,
                         FunctionCallingService functionCallingService) {
        this.chatModel = chatModel;
        this.chatClient = chatClient;
        this.coordinatorAgent = coordinatorAgent;
        this.agents = agents;
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
        this.functionCallingService = functionCallingService;
    }

    @PostMapping("/simple")
    public Map<String, Object> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String conversationId = request.getOrDefault("conversationId", "default");
        String model = request.getOrDefault("model", "deepseek-v4-flash");
        boolean useTools = Boolean.parseBoolean(request.getOrDefault("useTools", "false"));

        memoryService.addUserMessage(conversationId, message);
        List<Message> history = memoryService.getHistory(conversationId);

        String response;
        if (useTools) {
            response = functionCallingService.executeWithTools(message, model);
        } else {
            response = coordinatorAgent.execute(message, history);
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
        memoryService.addUserMessage(conversationId, message);
        return chatClient.prompt()
                .user(message)
                .options(ChatOptions.builder().model(model).build())
                .stream()
                .content();
    }

    @PostMapping("/agent/{agentName}")
    public Map<String, Object> chatWithAgent(@PathVariable String agentName,
                                              @RequestBody Map<String, String> request) {
        String message = request.get("message");
        String conversationId = request.getOrDefault("conversationId", "default");
        String model = request.getOrDefault("model", "deepseek-v4-flash");

        memoryService.addUserMessage(conversationId, message);
        List<Message> history = memoryService.getHistory(conversationId);

        Agent targetAgent = agents.get(agentName);
        if (targetAgent == null) {
            targetAgent = coordinatorAgent;
        }

        String response = targetAgent.execute(message, history);
        memoryService.addAssistantMessage(conversationId, response);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("response", response);
        result.put("agent", targetAgent.getName());
        result.put("conversationId", conversationId);
        result.put("model", model);
        return result;
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
        List<Map<String, String>> tools = new ArrayList<>();
        for (var tool : toolRegistry.getAllTools()) {
            tools.add(Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription()
            ));
        }
        return tools;
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
}
