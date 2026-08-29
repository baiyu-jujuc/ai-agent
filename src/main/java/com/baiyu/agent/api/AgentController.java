package com.baiyu.agent.api;

import com.baiyu.agent.agent.Agent;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final Map<String, Agent> agents;

    public AgentController(Map<String, Agent> agents) {
        this.agents = agents;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "agent", "AI Agent",
                "version", "0.0.1",
                "activeAgents", agents.size()
        );
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of(
                "default", "deepseek-v4-pro",
                "fast", "deepseek-v4-flash",
                "vision", "deepseek-v4-flash-vision-exp"
        );
    }

    @GetMapping("/list")
    public List<Map<String, String>> listAgents() {
        return agents.values().stream()
                .map(a -> Map.of("name", a.getName(), "description", a.getDescription()))
                .collect(Collectors.toList());
    }
}
