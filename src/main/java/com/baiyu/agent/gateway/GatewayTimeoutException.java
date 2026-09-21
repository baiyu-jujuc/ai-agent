package com.baiyu.agent.gateway;

/**
 * 网关层超时。
 *
 * <p>它必须和 HTTP 层超时区分开：{@code AiConfig} 里配的是 15s 连接 / 60s 读写，
 * 网关层默认 30s。如果网关超时不小于 HTTP 层超时，请求会被 HTTP 层先掐掉，
 * 熔断器就统计不到这次失败——这是文档里点名的常见坑之一。
 */
public class GatewayTimeoutException extends RuntimeException {

    public GatewayTimeoutException(String message) {
        super(message);
    }
}
