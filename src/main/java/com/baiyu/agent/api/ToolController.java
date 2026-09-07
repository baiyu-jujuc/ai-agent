package com.baiyu.agent.api;

import com.baiyu.agent.tool.ToolRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public List<Map<String, String>> listTools() {
        return toolRegistry.listTools();
    }

    @PostMapping("/{toolName}")
    public Map<String, String> executeTool(@PathVariable String toolName,
                                           @RequestBody Map<String, String> request) {
        String input = request.getOrDefault("input", "");
        try {
            return toolRegistry.execute(toolName, input)
                    .map(result -> Map.of("tool", toolName, "result", result))
                    .orElseGet(() -> Map.of("tool", toolName, "result", "Tool not found: " + toolName));
        } catch (Exception e) {
            return Map.of("tool", toolName, "result", "Execution failed, please try again.");
        }
    }
}
