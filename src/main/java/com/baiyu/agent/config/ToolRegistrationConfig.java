package com.baiyu.agent.config;

import com.baiyu.agent.tool.Tool;
import com.baiyu.agent.tool.ToolRegistry;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;
import java.util.List;

@Configuration
public class ToolRegistrationConfig {

    private final ToolRegistry toolRegistry;
    private final List<Tool> tools;

    public ToolRegistrationConfig(ToolRegistry toolRegistry, List<Tool> tools) {
        this.toolRegistry = toolRegistry;
        this.tools = tools;
    }

    @PostConstruct
    public void registerTools() {
        for (Tool tool : tools) {
            toolRegistry.register(tool);
        }
    }
}
