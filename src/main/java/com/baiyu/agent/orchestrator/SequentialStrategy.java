package com.baiyu.agent.orchestrator;

import com.baiyu.agent.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SequentialStrategy implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(SequentialStrategy.class);

    private final Map<String, Agent> agents;

    public SequentialStrategy(Map<String, Agent> agents) {
        this.agents = agents;
    }

    @Override
    public String getName() { return "sequential"; }

    @Override
    public Map<String, String> execute(String input, List<Message> context, List<String> agentNames) {
        Map<String, String> results = new LinkedHashMap<>();
        String accumulatedContext = input;

        for (String agentName : agentNames) {
            Agent agent = agents.get(agentName);
            if (agent == null) {
                log.warn("Agent not found: {}", agentName);
                results.put(agentName, "[error] agent not found");
                continue;
            }
            try {
                String result = agent.execute(accumulatedContext, context);
                results.put(agentName, result);
                accumulatedContext = result;
            } catch (Exception e) {
                log.error("Agent {} failed: {}", agentName, e.getMessage());
                results.put(agentName, "[error] " + e.getMessage());
            }
        }
        return results;
    }
}
