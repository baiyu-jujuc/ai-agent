package com.baiyu.agent.api;

import com.baiyu.agent.agent.Agent;
import com.baiyu.agent.config.ModelRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final Map<String, Agent> agents;
    private final ModelRegistry modelRegistry;

    @Value("${agent.version:0.0.1}")
    private String version;

    public AgentController(Map<String, Agent> agents, ModelRegistry modelRegistry) {
        this.agents = agents;
        this.modelRegistry = modelRegistry;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "agent", "AI Agent",
                "version", version,
                "activeAgents", agents.size()
        );
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of(
                "default", modelRegistry.getDefaultModel(),
                "available", modelRegistry.listModels()
        );
    }

    @GetMapping("/list")
    public List<Map<String, String>> listAgents() {
        return agents.values().stream()
                .map(a -> Map.of("name", a.getName(), "description", a.getDescription()))
                .collect(Collectors.toList());
    }
}
