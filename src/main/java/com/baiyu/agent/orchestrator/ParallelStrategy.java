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
        return executeWithTrace(input, context, agentNames).getAgentResults();
    }

    private record AgentResult(String name, String output, long duration, String status) {}

    @Override
    public OrchestrationResult executeWithTrace(String input, List<Message> context, List<String> agentNames) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<TaskTrace> traces = Collections.synchronizedList(new ArrayList<>());

        List<Future<AgentResult>> futures = new ArrayList<>();

        for (String agentName : agentNames) {
            Agent agent = agents.get(agentName);
            if (agent != null) {
                futures.add(executor.submit(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        String result = agent.execute(input, context);
                        long duration = System.currentTimeMillis() - start;
                        return new AgentResult(agentName, result, duration, "success");
                    } catch (Exception e) {
                        long duration = System.currentTimeMillis() - start;
                        log.error("Agent {} failed: {}", agentName, e.getMessage());
                        return new AgentResult(agentName, "[error] " + e.getMessage(), duration, "error");
                    }
                }));
            }
        }

        for (Future<AgentResult> f : futures) {
            try {
                AgentResult ar = f.get(timeoutSeconds, TimeUnit.SECONDS);
                results.put(ar.name(), ar.output());
                traces.add("success".equals(ar.status())
                        ? TaskTrace.success(ar.name(), input, ar.output(), ar.duration())
                        : TaskTrace.error(ar.name(), input, ar.output(), ar.duration()));
            } catch (TimeoutException e) {
                f.cancel(true);
                log.warn("Agent timed out after {}s", timeoutSeconds);
                traces.add(TaskTrace.error("unknown", input, "timeout after " + timeoutSeconds + "s", timeoutSeconds * 1000L));
            } catch (Exception e) {
                log.error("Agent execution failed: {}", e.getMessage());
                traces.add(TaskTrace.error("unknown", input, e.getMessage(), 0));
            }
        }
        return new OrchestrationResult("parallel", results, traces);
    }
}
