package com.baiyu.agent.agent;

import com.baiyu.agent.gateway.CallScene;
import com.baiyu.agent.gateway.GatewayRequest;
import com.baiyu.agent.gateway.ModelGateway;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 所有 Agent 的公共执行体——改造时杠杆最大的一处：
 * 改这一个类，等于收口了 CodeAgent / DataAgent / ReActAgent / ResearchAgent 等全部子类。
 */
public abstract class AbstractAgent implements Agent {

    protected final ModelGateway modelGateway;
    protected final String systemPrompt;
    protected Object[] agentTools = new Object[0];

    protected AbstractAgent(ModelGateway modelGateway, String systemPrompt) {
        this.modelGateway = modelGateway;
        this.systemPrompt = systemPrompt;
    }

    public void setTools(List<?> tools) {
        this.agentTools = tools != null ? tools.toArray() : new Object[0];
    }

    @Override
    public String execute(String input, List<Message> context) {
        return executeWithModel(input, null, context);
    }

    @Override
    public String executeWithModel(String input, String model, List<Message> context) {
        return executeWithModel(input, model, context, null);
    }

    @Override
    public String executeWithModel(String input, String model, List<Message> context, String clientApiKey) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (context != null) {
            messages.addAll(context);
        }
        messages.add(new UserMessage(input));

        GatewayRequest.Builder request = GatewayRequest.builder(CallScene.AGENT_EXECUTE)
                .messages(messages)
                .modelOverride(model)
                .clientApiKey(clientApiKey);
        if (agentTools.length > 0) {
            request.tools(Arrays.asList(agentTools));
        }
        return modelGateway.call(request.build()).content();
    }
}
