package com.baiyu.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractAgent implements Agent {

    protected final ChatClient chatClient;
    protected final String systemPrompt;

    protected AbstractAgent(ChatClient chatClient, String systemPrompt) {
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public String execute(String input, List<Message> context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (context != null) {
            messages.addAll(context);
        }
        messages.add(new UserMessage(input));

        return chatClient.prompt(new Prompt(messages))
                .call()
                .content();
    }

    public String executeWithModel(String input, String model) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(input)
                .options(ChatOptions.builder().model(model).build())
                .call()
                .content();
    }
}
