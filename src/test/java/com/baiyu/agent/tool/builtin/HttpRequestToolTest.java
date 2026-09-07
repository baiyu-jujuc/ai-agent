package com.baiyu.agent.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestToolTest {

    private HttpRequestTool httpTool;

    @BeforeEach
    void setUp() {
        httpTool = new HttpRequestTool();
    }

    @Test
    void localhostBlocked() {
        String result = httpTool.execute("http://localhost:8080");
        assertTrue(result.contains("安全拒绝") || result.contains("禁止访问"), result);
    }

    @Test
    void loopbackIpBlocked() {
        String result = httpTool.execute("http://127.0.0.1:8080");
        assertTrue(result.contains("安全拒绝") || result.contains("禁止访问"), result);
    }

    @Test
    void cloudMetadataBlocked() {
        String result = httpTool.execute("http://169.254.169.254/latest/meta-data/");
        assertTrue(result.contains("安全拒绝") || result.contains("禁止访问"), result);
    }

    @Test
    void googleMetadataInternalBlocked() {
        String result = httpTool.execute("http://metadata.google.internal");
        assertTrue(result.contains("安全拒绝") || result.contains("禁止访问"), result);
    }

    @Test
    void internalDomainBlocked() {
        String result = httpTool.execute("http://something.internal");
        assertTrue(result.contains("安全拒绝") || result.contains("禁止访问"), result);
    }

    @Test
    void emptyUrlRejected() {
        String result = httpTool.execute("");
        assertTrue(result.contains("请提供 URL"), result);
    }

    @Test
    void invalidUrlRejected() {
        String result = httpTool.execute("not-a-url-at-all");
        assertTrue(result.contains("失败") || result.contains("拒绝") || result.contains("无法"), result);
    }
}
