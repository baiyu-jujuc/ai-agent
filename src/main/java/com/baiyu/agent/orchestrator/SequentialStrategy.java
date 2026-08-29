package com.baiyu.agent.orchestrator;

import com.baiyu.agent.agent.Agent;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SequentialStrategy implements OrchestrationStrategy {

    private final Map<String, Agent> agents;

    public SequentialStrategy(Map<String, Agent> agents) {
        this.agents = agents;
    }

    @Override
    public String getName() { return "sequential"; }

    @Override
    public Map<String, String> execute(String input, List<Message> context, List<String> agentNames) {
        Map<String, String> results = new LinkedHashMap<>();
        String currentInput = input;

        for (String agentName : agentNames) {
            Agent agent = agents.get(agentName);
            if (agent != null) {
                String result = agent.execute(currentInput, context);
                results.put(agentName, result);
                currentInput = result;
            }
        }
        return results;
    }
}
