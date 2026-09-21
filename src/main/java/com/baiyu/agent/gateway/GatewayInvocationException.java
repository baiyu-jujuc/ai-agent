package com.baiyu.agent.gateway;

/** 网关调用失败（候选模型全部失败、或上游异常被包装）。 */
public class GatewayInvocationException extends RuntimeException {

    private final String modelId;

    public GatewayInvocationException(String message, Throwable cause) {
        super(message, cause);
        this.modelId = null;
    }

    private GatewayInvocationException(String message, String modelId, Throwable cause) {
        super(message, cause);
        this.modelId = modelId;
    }

    public static GatewayInvocationException forModel(String modelId, Throwable cause) {
        return new GatewayInvocationException(
                "模型 " + modelId + " 调用失败: " + (cause == null ? "unknown" : cause.getMessage()),
                modelId, cause);
    }

    public String getModelId() {
        return modelId;
    }
}
