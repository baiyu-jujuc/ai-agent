package com.baiyu.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

@Component
public class ToolRegistry {

    public record ToolInfo(String name, String description, ToolComponent component, Method method) {}

    private final Map<String, ToolInfo> tools = new LinkedHashMap<>();

    public ToolRegistry(List<ToolComponent> toolComponents) {
        for (ToolComponent tool : toolComponents) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation != null) {
                    String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
                    tools.put(name, new ToolInfo(name, annotation.description(), tool, method));
                }
            }
        }
    }

    public List<Map<String, String>> listTools() {
        List<Map<String, String>> result = new ArrayList<>();
        for (ToolInfo info : tools.values()) {
            result.add(Map.of("name", info.name(), "description", info.description()));
        }
        return result;
    }

    public Optional<String> execute(String toolName, String input) {
        ToolInfo info = tools.get(toolName);
        if (info == null) {
            return Optional.empty();
        }
        try {
            Object result = info.method().invoke(info.component(), input);
            return Optional.of(result != null ? result.toString() : "empty result");
        } catch (Exception e) {
            throw new RuntimeException("Tool execution failed: " + toolName, e);
        }
    }

    public ToolComponent[] components() {
        return tools.values().stream()
                .map(ToolInfo::component)
                .distinct()
                .toArray(ToolComponent[]::new);
    }
}
