package com.baiyu.agent.gateway;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 一次模型调用的入参。业务层只描述"我要问什么"，不再关心用哪个 ChatClient、走不走缓存、要不要限流。
 *
 * @param scene         调用场景（成本分析维度，必填）
 * @param systemPrompt  系统提示词，可为 null
 * @param userPrompt    单轮提问，可为 null（用 messages 时）
 * @param messages      多轮消息（Agent / 工具调用场景），可为 null
 * @param tools         工具列表（Function Calling 场景），可为 null
 * @param modelOverride 指定模型；为 null 时按路由策略选
 * @param spaceId       知识空间（计量分组 + 缓存隔离的必要维度）
 * @param userId        用户
 * @param conversationId 会话
 * @param clientApiKey  客户端自带 Key（沿用 allow-client-model-key 逻辑，不落库不打印）
 * @param promptKey     Prompt 模板 key（L3，可空）
 * @param promptVersion Prompt 模板版本（L3，可空）
 * @param temperature   采样温度（可空，例如协调器路由需要 0.0 提高稳定性）
 */
public record GatewayRequest(
        CallScene scene,
        String systemPrompt,
        String userPrompt,
        List<Message> messages,
        List<Object> tools,
        String modelOverride,
        String spaceId,
        String userId,
        String conversationId,
        String clientApiKey,
        String promptKey,
        Integer promptVersion,
        Double temperature
) {

    public static Builder builder(CallScene scene) {
        return new Builder(scene);
    }

    public boolean hasMessages() {
        return messages != null && !messages.isEmpty();
    }

    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    public boolean hasClientApiKey() {
        return clientApiKey != null && !clientApiKey.isBlank();
    }

    /** 用于计量的"问题摘要"长度参考：流式拿不到真实 usage 时按字符数估算。 */
    public String promptText() {
        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null) {
            sb.append(systemPrompt);
        }
        if (messages != null) {
            for (Message message : messages) {
                if (message != null && message.getText() != null) {
                    sb.append(message.getText());
                }
            }
        }
        if (userPrompt != null) {
            sb.append(userPrompt);
        }
        return sb.toString();
    }

    public static final class Builder {

        private final CallScene scene;
        private String systemPrompt;
        private String userPrompt;
        private List<Message> messages;
        private List<Object> tools;
        private String modelOverride;
        private String spaceId;
        private String userId;
        private String conversationId;
        private String clientApiKey;
        private String promptKey;
        private Integer promptVersion;
        private Double temperature;

        private Builder(CallScene scene) {
            this.scene = scene;
        }

        public Builder systemPrompt(String value) {
            this.systemPrompt = value;
            return this;
        }

        public Builder userPrompt(String value) {
            this.userPrompt = value;
            return this;
        }

        public Builder messages(List<Message> value) {
            this.messages = value;
            return this;
        }

        public Builder tools(List<Object> value) {
            this.tools = value;
            return this;
        }

        public Builder modelOverride(String value) {
            this.modelOverride = value;
            return this;
        }

        public Builder spaceId(String value) {
            this.spaceId = value;
            return this;
        }

        public Builder userId(String value) {
            this.userId = value;
            return this;
        }

        public Builder conversationId(String value) {
            this.conversationId = value;
            return this;
        }

        public Builder clientApiKey(String value) {
            this.clientApiKey = value;
            return this;
        }

        public Builder promptTemplate(String key, Integer version) {
            this.promptKey = key;
            this.promptVersion = version;
            return this;
        }

        public Builder temperature(Double value) {
            this.temperature = value;
            return this;
        }

        public GatewayRequest build() {
            return new GatewayRequest(scene, systemPrompt, userPrompt, messages, tools, modelOverride,
                    spaceId, userId, conversationId, clientApiKey, promptKey, promptVersion, temperature);
        }
    }
}
