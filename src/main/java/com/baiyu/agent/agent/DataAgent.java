package com.baiyu.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component("data")
public class DataAgent extends AbstractAgent {

    public DataAgent(ChatClient chatClient, List<com.baiyu.agent.tool.ToolComponent> allTools) {
        super(chatClient, """
                你是一个数据分析 Agent。你的能力：
                - 分析数据并提供洞察
                - 生成 SQL 查询
                - 创建数据摘要和报表
                - 建议数据可视化方案
                - 识别趋势、模式和异常
                
                始终逐步解释你的分析过程。
                适当的时候用表格格式化数据。用中文回答。
                """);
        setTools(filterTools(allTools, "calculator", "systeminfo", "time"));
    }

    private static List<?> filterTools(List<com.baiyu.agent.tool.ToolComponent> all, String... names) {
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
    public String getName() { return "data"; }

    @Override
    public String getDescription() { return "数据分析、数据库查询和报表生成"; }
}
