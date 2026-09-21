package com.baiyu.agent.tool;

import com.baiyu.agent.gateway.CallScene;
import com.baiyu.agent.gateway.GatewayRequest;
import com.baiyu.agent.gateway.ModelGateway;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class FunctionCallingService {

    private final ModelGateway modelGateway;
    private final ToolRegistry toolRegistry;

    public FunctionCallingService(ModelGateway modelGateway, ToolRegistry toolRegistry) {
        this.modelGateway = modelGateway;
        this.toolRegistry = toolRegistry;
    }

    public String executeWithTools(String userInput, String model, List<Message> history) {
        return executeWithTools(userInput, model, history, null);
    }

    public String executeWithTools(String userInput, String model, List<Message> history, String clientApiKey) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("你是多功能AI助手，可以调用工具来帮助用户。" +
                "需要时主动调用合适的工具，并根据工具结果用用户的语言给出完整回答。" +
                "不需要工具时直接回答。"));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(userInput));

        List<Object> tools = Arrays.asList((Object[]) toolRegistry.components());
        return modelGateway.call(GatewayRequest.builder(CallScene.FUNCTION_CALLING)
                .messages(messages)
                .tools(tools)
                .modelOverride(model)
                .clientApiKey(clientApiKey)
                .build()).content();
    }
}
