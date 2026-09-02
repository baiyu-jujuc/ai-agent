package com.baiyu.agent.agent;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface Agent {

    String getName();

    String getDescription();

    String execute(String input, List<Message> context);

    default String executeWithModel(String input, String model, List<Message> context) {
        return execute(input, context);
    }
}
