package com.baiyu.agent.api;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatModel chatModel;

    public ChatController(ChatModel chatModel) {
        this.chatModel = ChatClient.create(chatModel);
        this.chatModel = chatModel;
    }

    @PostMapping("/simple")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String response = chatClient.prompt()
                .user(message)
                .call()
                .content();
        return Map.of("response", response);
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    public Flux<String> streamChat(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }

    @PostMapping(value = "/sse", produces = "text/event-stream")
    public SseEmitter sseChat(@RequestBody Map<String, String> request) {
        SseEmitter emitter = new SseEmitter(60000L);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                String message = request.get("message");
                chatModel.stream(new Prompt(new UserMessage(message)))
                        .toIterable()
                        .forEach(chunk -> {
                            try {
                                String content = chunk.getResult().getOutput().getText();
                                if (content != null) {
                                    emitter.send(SseEmitter.event().data(content));
                                }
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                executor.shutdown();
            }
        });

        return emitter;
    }
}
