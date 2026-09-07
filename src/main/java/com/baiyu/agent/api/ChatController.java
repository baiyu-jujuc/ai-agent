package com.baiyu.agent.api;

import com.baiyu.agent.agent.Agent;
import com.baiyu.agent.agent.CoordinatorAgent;
import com.baiyu.agent.config.ModelRegistry;
import com.baiyu.agent.memory.ChatMemoryService;
import com.baiyu.agent.orchestrator.OrchestrationResult;
import com.baiyu.agent.orchestrator.OrchestrationStrategy;
import com.baiyu.agent.rag.RagService;
import com.baiyu.agent.tool.FunctionCallingService;
import com.baiyu.agent.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
    private final RagService ragService;
    private final Map<String, OrchestrationStrategy> strategies;
    private final ModelRegistry modelRegistry;

    public ChatController(ChatModel chatModel, ChatClient chatClient,
                         CoordinatorAgent coordinatorAgent,
                         Map<String, Agent> agents,
                         ChatMemoryService memoryService,
                         ToolRegistry toolRegistry,
                         FunctionCallingService functionCallingService,
                         RagService ragService,
                         Map<String, OrchestrationStrategy> strategies,
                         ModelRegistry modelRegistry) {
        this.chatModel = chatModel;
        this.chatClient = chatClient;
        this.coordinatorAgent = coordinatorAgent;
        this.agents = agents;
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
        this.functionCallingService = functionCallingService;
        this.ragService = ragService;
        this.strategies = strategies;
        this.modelRegistry = modelRegistry;
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
        String model = request.getOrDefault("model", modelRegistry.getDefaultModel());
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
            response = "请求失败，请稍后重试。";
        }
        memoryService.addAssistantMessage(conversationId, response);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("response", response);
        result.put("conversationId", conversationId);
        result.put("model", model);
        result.put("toolsUsed", useTools);
        return result;
    }

    /**
     * B3: Two distinct streaming paths:
     * 1. Normal streaming (useTools=false, agent=coordinator): true SSE streaming via chatClient.stream()
     * 2. Tool/agent mode (useTools=true or agent!=coordinator): blocking call, result emitted as single SSE event
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestParam String message,
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "coordinator") String agent,
            @RequestParam(defaultValue = "false") boolean useTools) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message 超过最大长度限制");
        }
        String resolvedModel = (model == null || model.isBlank()) ? modelRegistry.getDefaultModel() : model;

        List<Message> history = memoryService.getHistory(conversationId);
        memoryService.addUserMessage(conversationId, message);

        StringBuilder reply = new StringBuilder();
        Flux<String> contentFlux;

        boolean useToolPath = useTools || (!"coordinator".equals(agent) && agents.containsKey(agent));

        if (useToolPath) {
            // B3: Tool/agent path — blocking, result as single event
            Agent targetAgent = agents.getOrDefault(agent, coordinatorAgent);
            try {
                String result = useTools
                        ? functionCallingService.executeWithTools(message, resolvedModel, history)
                        : targetAgent.executeWithModel(message, resolvedModel, history);
                contentFlux = Flux.just(result == null ? "" : result);
            } catch (Exception e) {
                contentFlux = Flux.just("Agent 执行失败，请稍后重试。");
            }
        } else {
            // B3: True streaming path via chatClient
            contentFlux = chatClient.prompt()
                    .messages(history)
                    .user(message)
                    .options(ChatOptions.builder().model(resolvedModel).build())
                    .stream()
                    .content();
        }

        return contentFlux
                .doOnNext(reply::append)
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())
                .doOnComplete(() -> {
                    String finalReply = reply.toString();
                    if (!finalReply.isBlank()) {
                        memoryService.addAssistantMessage(conversationId, finalReply);
                    }
                })
                .doOnError(e -> memoryService.addAssistantMessage(conversationId, "[stream error] " + e.getMessage()))
                .concatWith(Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build()));
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
        String model = request.getOrDefault("model", modelRegistry.getDefaultModel());

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
            response = "Agent 执行失败，请稍后重试。";
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
        Object agentsParam = request.get("agents");
        List<String> agentNames;
        if (agentsParam instanceof List<?> list) {
            agentNames = list.stream().map(Object::toString).toList();
        } else {
            agentNames = List.of("code", "research");
        }
        if (agentNames.isEmpty()) {
            throw new IllegalArgumentException("agents 不能为空");
        }
        String conversationId = (String) request.getOrDefault("conversationId", "default");

        OrchestrationStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            strategy = strategies.get(strategyName + "Strategy");
        }
        if (strategy == null) {
            return Map.of("error", "Unknown strategy: " + strategyName + ". Available: " + strategies.keySet());
        }

        List<Message> history = memoryService.getHistory(conversationId);
        memoryService.addUserMessage(conversationId, input);
        OrchestrationResult orchResult = strategy.executeWithTrace(input, history, agentNames);

        String combined = orchResult.getAgentResults().values().stream()
                .reduce((a, b) -> a + "\n\n---\n\n" + b)
                .orElse("No results");
        memoryService.addAssistantMessage(conversationId, combined);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategyName);
        result.put("agentResults", orchResult.getAgentResults());
        result.put("traces", orchResult.getTraces());
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
        return modelRegistry.listModels();
    }

    @GetMapping("/tools")
    public List<Map<String, String>> getTools() {
        return toolRegistry.listTools();
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
