package com.baiyu.agent.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolConfig {
    // Tool beans are collected via List<ToolComponent> injection where needed.
    // Spring AI 1.0 uses .tools(Object...) directly, no ToolCallback[] bean required.
}
