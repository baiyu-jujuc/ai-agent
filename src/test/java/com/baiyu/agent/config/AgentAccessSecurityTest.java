package com.baiyu.agent.config;

import com.baiyu.agent.user.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-1 安全前置的回归测试（规格 2026-10-03）：
 *
 * <ol>
 *   <li><b>匿名访问 /api/agent/** → 401</b>：原来 GET /api/agent/** 是 permitAll，
 *       一旦新增 POST /api/agent/{name}（Agent 执行入口）就会落到 anyRequest → 匿名可调用，
 *       既烧 Token，又能触发内置工具；</li>
 *   <li><b>新路径默认拒绝</b>：anyRequest 由 permitAll 改成 denyAll，
 *       没有显式放行的新接口，匿名拿 401、已登录普通用户拿 403（不会"默默公开"）；</li>
 *   <li><b>单页 UI 仍可用</b>：静态页面路径显式放行，浏览器还能匿名打开首页。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://localhost:1",
        "spring.ai.openai.chat.model=deepseek-v4-flash",
        "agent.storage.vector-store=memory",
        "agent.storage.memory=memory",
        "agent.security.api-key=test-secret-key",
        "agent.security.rate-limit-per-minute=200",
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@AutoConfigureMockMvc
class AgentAccessSecurityTest {

    private static final String API_KEY = "test-secret-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    private String tokenFor(String role) {
        return jwtUtil.generateToken("user-" + role, role + "-tester", role);
    }

    // ---------------------------------------------------------------- /api/agent/**

    @Test
    void anonymousAgentListReturns401() throws Exception {
        mockMvc.perform(get("/api/agent/list"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAgentHealthReturns401() throws Exception {
        // 这条以前是"公开的"，收紧后必须登录
        mockMvc.perform(get("/api/agent/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanStillListAgents() throws Exception {
        // 收紧的是"匿名"，不是"只读"：登录后这三个只读接口照常可用
        mockMvc.perform(get("/api/agent/list")
                        .header("X-API-Key", API_KEY)
                        .header("Authorization", "Bearer " + tokenFor("user")))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousAgentExecuteReturns401() throws Exception {
        // 未来才会实现的执行入口（P0-2）：现在就必须匿名拿 401，而不是 200/404
        mockMvc.perform(post("/api/agent/react")
                        .contentType("application/json")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 新路径默认拒绝

    @Test
    void unknownNewPathIsDeniedByDefault_anonymous() throws Exception {
        mockMvc.perform(get("/api/not-implemented-yet/whatever"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownNewPathIsDeniedByDefault_authenticated() throws Exception {
        mockMvc.perform(get("/api/not-implemented-yet/whatever")
                        .header("X-API-Key", API_KEY)
                        .header("Authorization", "Bearer " + tokenFor("user")))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- 单页 UI 不能被误伤

    @Test
    void indexPageStaysPublic() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk());
    }
}
