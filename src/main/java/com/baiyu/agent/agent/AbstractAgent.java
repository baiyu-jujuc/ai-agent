package com.baiyu.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractAgent implements Agent {

    protected final ChatClient chatClient;
    protected final String systemPrompt;
    protected List<?> tools = List.of();

    protected AbstractAgent(ChatClient chatClient, String systemPrompt) {
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
    }

    public void setTools(List<?> tools) {
        this.tools = tools != null ? tools : List.of();
    }

    @Override
    public String execute(String input, List<Message> context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (context != null) {
            messages.addAll(context);
        }
        messages.add(new UserMessage(input));

        var spec = chatClient.prompt(new Prompt(messages));
        if (!tools.isEmpty()) {
            spec.tools(tools.toArray());
        }
        return spec.call().content();
    }

    public String executeWithModel(String input, String model, List<Message> context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (context != null) {
            messages.addAll(context);
        }
        messages.add(new UserMessage(input));

        var spec = chatClient.prompt(new Prompt(messages))
                .options(ChatOptions.builder().model(model).build());
        if (!tools.isEmpty()) {
            spec.tools(tools.toArray());
        }
        return spec.call().content();
    }
}
