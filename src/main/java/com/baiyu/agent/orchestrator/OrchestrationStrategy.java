package com.baiyu.agent.orchestrator;

import org.springframework.ai.chat.messages.Message;
import java.util.List;
import java.util.Map;

public interface OrchestrationStrategy {

    String getName();

    Map<String, String> execute(String input, List<Message> context, List<String> agentNames);
}
