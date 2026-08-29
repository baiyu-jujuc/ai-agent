package com.baiyu.agent.api;

import com.baiyu.agent.agent.Agent;
import com.baiyu.agent.agent.CoordinatorAgent;
import com.baiyu.agent.memory.ChatMemoryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final CoordinatorAgent coordinatorAgent;
    private final Map<String, Agent> agents;
    private final ChatMemoryService memoryService;

    public ChatController(ChatModel chatModel, ChatClient chatClient,
                         CoordinatorAgent coordinatorAgent,
                         Map<String, Agent> agents,
                         ChatMemoryService memoryService) {
        this.chatModel = chatModel;
        this.chatClient = chatClient;
        this.coordinatorAgent = coordinatorAgent;
        this.agents = agents;
        this.memoryService = memoryService;
    }

    @PostMapping("/simple")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String conversationId = request.getOrDefault("conversationId", "default");

        memoryService.addUserMessage(conversationId, message);
        List<Message> history = memoryService.getHistory(conversationId);

        String response = coordinatorAgent.execute(message, history);
        memoryService.addAssistantMessage(conversationId, response);

        return Map.of("response", response, "conversationId", conversationId);
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    public Flux<String> streamChat(@RequestParam String message,
                                    @RequestParam(defaultValue = "default") String conversationId) {
        memoryService.addUserMessage(conversationId, message);
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }

    @PostMapping("/agent/{agentName}")
    public Map<String, String> chatWithAgent(@PathVariable String agentName,
                                              @RequestBody Map<String, String> request) {
        String message = request.get("message");
        String conversationId = request.getOrDefault("conversationId", "default");

        memoryService.addUserMessage(conversationId, message);
        List<Message> history = memoryService.getHistory(conversationId);

        Agent targetAgent = agents.get(agentName);
        if (targetAgent == null) {
            targetAgent = coordinatorAgent;
        }

        String response = targetAgent.execute(message, history);
        memoryService.addAssistantMessage(conversationId, response);

        return Map.of("response", response, "agent", targetAgent.getName(), "conversationId", conversationId);
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
