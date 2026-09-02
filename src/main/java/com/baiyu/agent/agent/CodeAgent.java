package com.baiyu.agent.agent;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component("code")
public class CodeAgent extends AbstractAgent {

    public CodeAgent(ChatClient chatClient, List<ToolComponent> allTools) {
        super(chatClient, """
                你是一个代码专家 Agent。你的能力：
                - 编写干净、可运行的 Java/Python/JavaScript 代码
                - 调试和修复代码问题
                - 清晰解释编程概念
                - 建议最佳实践和设计模式
                - 从安全、性能、可维护性角度审查代码

                始终提供完整、可运行的代码示例并附带解释。
                用 markdown 代码块输出代码。用中文回答。
                """);
        setTools(filterTools(allTools, "calculator", "file", "systeminfo"));
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
    public String getName() { return "code"; }

    @Override
    public String getDescription() { return "代码生成、调试和技术辅助"; }
}
