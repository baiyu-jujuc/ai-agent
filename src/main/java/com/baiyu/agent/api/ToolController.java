package com.baiyu.agent.api;

import com.baiyu.agent.tool.Tool;
import com.baiyu.agent.tool.ToolRegistry;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public List<Map<String, String>> listTools() {
        return toolRegistry.getAllTools().stream()
                .map(t -> Map.of("name", t.getName(), "description", t.getDescription()))
                .collect(Collectors.toList());
    }

    @PostMapping("/{toolName}")
    public Map<String, String> executeTool(@PathVariable String toolName,
                                           @RequestBody Map<String, String> request) {
        String input = request.getOrDefault("input", "");
        String result = toolRegistry.executeTool(toolName, input);
        return Map.of("tool", toolName, "result", result);
    }
}
