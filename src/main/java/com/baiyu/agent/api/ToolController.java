package com.baiyu.agent.api;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final List<ToolComponent> toolComponents;

    public ToolController(List<ToolComponent> toolComponents) {
        this.toolComponents = toolComponents;
    }

    @GetMapping
    public List<Map<String, String>> listTools() {
        List<Map<String, String>> result = new ArrayList<>();
        for (Object tool : toolComponents) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation != null) {
                    String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
                    result.add(Map.of("name", name, "description", annotation.description()));
                }
            }
        }
        return result;
    }

    @PostMapping("/{toolName}")
    public Map<String, String> executeTool(@PathVariable String toolName,
                                           @RequestBody Map<String, String> request) {
        for (Object tool : toolComponents) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation != null) {
                    String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
                    if (name.equals(toolName)) {
                        try {
                            String input = request.getOrDefault("input", "");
                            Object result = method.invoke(tool, input);
                            return Map.of("tool", toolName, "result", result != null ? result.toString() : "empty result");
                        } catch (Exception e) {
                            Throwable cause = e.getCause();
                            String errMsg = cause != null ? cause.getMessage() : e.getMessage();
                            return Map.of("tool", toolName, "result", "Execution error: " + (errMsg != null ? errMsg : "unknown"));
                        }
                    }
                }
            }
        }
        return Map.of("tool", toolName, "result", "Tool not found: " + toolName);
    }
}
