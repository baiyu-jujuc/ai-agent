package com.baiyu.agent.agent;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Agent 抽象。
 *
 * <p>改造后这里不再出现 {@code ChatClient}：Agent 只描述"我要问什么、指定哪个模型"，
 * 由 {@code ModelGateway} 负责怎么调用、要不要计量、失败了怎么降级。
 * {@code clientApiKey} 参数用于"客户端自带 Key"场景（仅当 allow-client-model-key=true 才生效）。
 */
public interface Agent {

    String getName();

    String getDescription();

    String execute(String input, List<Message> context);

    String executeWithModel(String input, String model, List<Message> context);

    String executeWithModel(String input, String model, List<Message> context, String clientApiKey);
}
