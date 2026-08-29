package com.baiyu.agent.tool;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    public List<String> getToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    public String executeTool(String name, String input) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "Tool not found: " + name;
        }
        return tool.execute(input);
    }
}
