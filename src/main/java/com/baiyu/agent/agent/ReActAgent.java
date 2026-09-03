package com.baiyu.agent.agent;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("react")
public class ReActAgent extends AbstractAgent {

    public ReActAgent(ChatClient chatClient, List<ToolComponent> allTools) {
        super(chatClient, """
                你是一个 ReAct (推理+行动) Agent。
                先逐步推理再行动，需要时使用可用工具。
                框架自动处理工具调用循环：决定调用哪个工具、审查观察结果、
                继续直到能给出最终答案。用用户的语言回答。
                """);
        setTools(allTools);
    }

    @Override
    public String getName() { return "react"; }

    @Override
    public String getDescription() { return "ReAct agent with reasoning-action loop (native function calling)"; }
}
