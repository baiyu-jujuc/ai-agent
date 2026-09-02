package com.baiyu.agent.agent;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component("research")
public class ResearchAgent extends AbstractAgent {

    public ResearchAgent(ChatClient chatClient, List<ToolComponent> allTools) {
        super(chatClient, """
                你是一个研究 Agent。你的能力：
                - 用准确、最新的信息回答问题
                - 综合多个来源的信息
                - 提供结构化、条理清晰的回复
                - 尽量引用来源
                - 识别知识空白并提出后续问题

                回答要全面但简洁。用中文回答。
                """);
        setTools(filterTools(allTools, "web-search", "http", "weather"));
    }

    private static List<?> filterTools(List<ToolComponent> all, String... names) {
        return all.stream()
                .filter(tool -> {
                    for (var method : tool.getClass().getDeclaredMethods()) {
                        Tool t = method.getAnnotation(Tool.class);
                        if (t != null) {
                            String name = t.name().isEmpty() ? method.getName() : t.name();
                            if (Arrays.asList(names).contains(name)) return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    @Override
    public String getName() { return "research"; }

    @Override
    public String getDescription() { return "信息检索和知识综合"; }
}
