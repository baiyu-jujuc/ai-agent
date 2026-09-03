package com.baiyu.agent.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FunctionCallingService {

    private final ChatClient chatClient;
    private final List<ToolComponent> toolComponents;

    public FunctionCallingService(ChatClient chatClient, List<ToolComponent> toolComponents) {
        this.chatClient = chatClient;
        this.toolComponents = toolComponents;
    }

    public String executeWithTools(String userInput, String model, List<Message> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("你是多功能AI助手，可以调用工具来帮助用户。" +
                "需要时主动调用合适的工具，并根据工具结果用用户的语言给出完整回答。" +
                "不需要工具时直接回答。"));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(userInput));

        var spec = chatClient.prompt()
                .messages(messages)
                .tools(toolComponents.toArray());

        if (model != null && !model.isEmpty()) {
            spec.options(ChatOptions.builder().model(model).build());
        }
        return spec.call().content();
    }
}
