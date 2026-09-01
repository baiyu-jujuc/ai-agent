package com.baiyu.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class AbstractAgent implements Agent {

    protected final ChatClient chatClient;
    protected final String systemPrompt;
    protected Object[] agentTools = new Object[0];

    protected AbstractAgent(ChatClient chatClient, String systemPrompt) {
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
    }

    public void setTools(List<?> tools) {
        this.agentTools = tools.toArray();
    }

    @Override
    public String execute(String input, List<Message> context) {
        return executeWithModel(input, null, context);
    }

    @Override
    public String executeWithModel(String input, String model, List<Message> context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (context != null) {
            messages.addAll(context);
        }
        messages.add(new UserMessage(input));

        var spec = chatClient.prompt(new Prompt(messages));
        if (model != null && !model.isEmpty()) {
            spec.options(ChatOptions.builder().model(model).build());
        }
        if (agentTools.length > 0) {
            spec.tools(agentTools);
        }
        return spec.call().content();
    }
}
