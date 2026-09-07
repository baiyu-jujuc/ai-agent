package com.baiyu.agent.orchestrator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OrchestrationResult {

    private final String strategy;
    private final Map<String, String> agentResults = new LinkedHashMap<>();
    private final List<TaskTrace> traces;

    public OrchestrationResult(String strategy, Map<String, String> agentResults, List<TaskTrace> traces) {
        this.strategy = strategy;
        if (agentResults != null) {
            this.agentResults.putAll(agentResults);
        }
        this.traces = traces;
    }

    public String getStrategy() { return strategy; }
    public Map<String, String> getAgentResults() { return agentResults; }
    public List<TaskTrace> getTraces() { return traces; }
}
