package com.baiyu.agent.orchestrator;

import com.baiyu.agent.agent.Agent;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Component
public class ParallelStrategy implements OrchestrationStrategy {

    private final Map<String, Agent> agents;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public ParallelStrategy(Map<String, Agent> agents) {
        this.agents = agents;
    }

    @Override
    public String getName() { return "parallel"; }

    @Override
    public Map<String, String> execute(String input, List<Message> context, List<String> agentNames) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (String agentName : agentNames) {
            Agent agent = agents.get(agentName);
            if (agent != null) {
                futures.add(executor.submit(() -> {
                    String result = agent.execute(input, context);
                    results.put(agentName, result);
                }));
            }
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        return results;
    }
}
