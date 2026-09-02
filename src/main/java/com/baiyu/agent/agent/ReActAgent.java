package com.baiyu.agent.agent;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    @Override
    public String execute(String input, List<Message> context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (context != null && !context.isEmpty()) {
            messages.addAll(context);
        }
        messages.add(new UserMessage(input));

        var spec = chatClient.prompt(new Prompt(messages));
        if (!tools.isEmpty()) {
            spec.tools(tools.toArray());
        }
        return spec.call().content();
    }
}
