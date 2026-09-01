package com.baiyu.agent.orchestrator;

import com.baiyu.agent.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Component
public class ParallelStrategy implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ParallelStrategy.class);

    private final Map<String, Agent> agents;
    private final ExecutorService executor;

    @Value("${agent.timeout-seconds:60}")
    private int timeoutSeconds;

    public ParallelStrategy(Map<String, Agent> agents) {
        this.agents = agents;
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "parallel-strategy");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String getName() { return "parallel"; }

    @Override
    public Map<String, String> execute(String input, List<Message> context, List<String> agentNames) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<Future<Map.Entry<String, String>>> futures = new ArrayList<>();

        for (String agentName : agentNames) {
            Agent agent = agents.get(agentName);
            if (agent != null) {
                futures.add(executor.submit(() -> {
                    try {
                        String result = agent.execute(input, context);
                        return Map.entry(agentName, result);
                    } catch (Exception e) {
                        log.error("Agent {} failed: {}", agentName, e.getMessage());
                        return Map.entry(agentName, "[error] " + e.getMessage());
                    }
                }));
            }
        }

        for (Future<Map.Entry<String, String>> f : futures) {
            try {
                Map.Entry<String, String> entry = f.get(timeoutSeconds, TimeUnit.SECONDS);
                results.put(entry.getKey(), entry.getValue());
            } catch (TimeoutException e) {
                f.cancel(true);
                log.warn("Agent timed out after {}s", timeoutSeconds);
            } catch (Exception e) {
                log.error("Agent execution failed: {}", e.getMessage());
            }
        }
        return results;
    }
}
