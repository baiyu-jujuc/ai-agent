package com.baiyu.agent.api;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "agent", "AI Agent",
                "version", "0.0.1"
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
}
