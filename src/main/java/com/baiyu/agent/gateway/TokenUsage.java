package com.baiyu.agent.gateway;

/**
 * 一次调用的 token 用量。
 *
 * <p>{@code source} 是这套设计里最容易被忽略、但面试最容易被追问的字段：
 * {@link #SOURCE_PROVIDER} 是供应商返回的真实值，{@link #SOURCE_ESTIMATED} 是我们自己估的。
 * 两者混在一起统计，成本数据就是假的。
 */
public record TokenUsage(String source, int promptTokens, int completionTokens) {

    public static final String SOURCE_PROVIDER = "PROVIDER";
    public static final String SOURCE_ESTIMATED = "ESTIMATED";

    public static TokenUsage provider(int promptTokens, int completionTokens) {
        return new TokenUsage(SOURCE_PROVIDER, promptTokens, completionTokens);
    }

    public static TokenUsage estimated(int promptTokens, int completionTokens) {
        return new TokenUsage(SOURCE_ESTIMATED, promptTokens, completionTokens);
    }

    public static TokenUsage empty() {
        return new TokenUsage(SOURCE_ESTIMATED, 0, 0);
    }

    public int total() {
        return promptTokens + completionTokens;
    }
}
