package com.baiyu.agent.probe;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * T3 探针：Agent 的工具轨迹到底能不能拿到（决定方案 A′ 还是 B）。
 *
 * <pre>
 * mvn -B -Dprobe.tool.trace=true -Dtest=ToolTraceProbeTest -DfailIfNoTests=false test
 * </pre>
 *
 * <p>默认跳过，和 {@code ProviderUsageProbeTest} 一样：它是"当时怎么验证的"证据，跑完保留在仓库里。
 * 会真实调用模型（消耗少量 token）。
 *
 * <p><b>2026-10-05 实测结论（判定：选 A′，不选 A、也不选 B）</b>
 * <ul>
 *   <li>(a) 非流式工具调用的最终 {@code ChatResponse.getToolCalls()} = <b>0 条</b> → 方案 A 不可行；</li>
 *   <li>(b) 框架走的是单参 {@code call(String)}，{@code ToolContext} 为 <b>null</b>（探针里 {@code history=-1} 就是
 *       这个意思）→ 拿不到 {@code getToolCallHistory()}；但装饰器已经把「工具名 / 入参 / 返回值 / 耗时」全部记下来，
 *       轨迹需求已满足，ToolContext 只是"能少写点代码"的加分项；</li>
 *   <li>(c) 两个并发请求各自持有装饰器与记录表，轨迹<b>隔离</b>（没有用 ThreadLocal）。</li>
 * </ul>
 * 因此实施口径：{@code ToolCallTrace} 由 {@code chatWithTrace} 在<b>每个请求内创建</b>（禁止单例 Bean、禁止裸
 * ThreadLocal）；{@code steps} 只回摘要、不回传原始入参出参；每请求上下文（含 T4 的角色）用按请求闭包携带，
 * 不依赖 ToolContext。设计见规格 §2 P0-2（spike 实测结论）。
 *
 * <p>验三件事：
 * <ol>
 *   <li><b>a</b> 非流式工具调用的最终 {@code ChatResponse} 里，{@code getToolCalls()} 有没有值；</li>
 *   <li><b>b</b> 工具执行时 {@code ToolContext.getToolCallHistory()} 里有没有之前的工具响应；</li>
 *   <li><b>c</b> 两个并发请求的轨迹是否互相隔离（用 {@code ToolContext} 显式传参，而不是裸 ThreadLocal）。</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "probe.tool.trace", matches = "true")
class ToolTraceProbeTest {

    private static final String TAG = "[TOOL-PROBE]";
    private static final StringBuilder REPORT = new StringBuilder();
    private static final Path REPORT_FILE = Path.of("target", "probe", "tool-trace-probe.txt");

    private static String apiKey;
    private static String baseUrl;
    private static String modelName;

    private static void report(String line) {
        System.out.println(line);
        REPORT.append(line).append(System.lineSeparator());
    }

    @BeforeAll
    static void loadCredentials() throws IOException {
        Map<String, String> dotenv = readDotEnv();
        apiKey = firstNonBlank(System.getenv("DEEPSEEK_API_KEY"), dotenv.get("DEEPSEEK_API_KEY"));
        baseUrl = firstNonBlank(System.getenv("SPRING_AI_OPENAI_BASE_URL"), "https://api.deepseek.com");
        modelName = firstNonBlank(System.getenv("DEFAULT_MODEL"), dotenv.get("DEFAULT_MODEL"), "deepseek-chat");
        if (apiKey == null) {
            throw new IllegalStateException("未找到 DEEPSEEK_API_KEY（环境变量与 .env 都没有）");
        }
        report(TAG + " baseUrl=" + baseUrl + " model=" + modelName);
    }

    @AfterAll
    static void writeReport() throws IOException {
        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(REPORT_FILE, REPORT.toString(), StandardCharsets.UTF_8);
        System.out.println(TAG + " 报告已写入 " + REPORT_FILE.toAbsolutePath());
    }

    @Test
    void probeToolCallsAndToolContextHistory() {
        ToolRecorder recorder = new ToolRecorder("A");
        ProbeTools tools = new ProbeTools();
        ChatClient client = ChatClient.builder(chatModel()).build();

        ChatResponse response = client.prompt()
                .user("请调用 probe_echo 工具，把字符串 hello-trace 原样返回；拿到结果后只回答工具返回的内容。")
                .toolCallbacks(recorder.wrap(tools))
                .call()
                .chatResponse();

        List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
        report(TAG + " (a) 最终响应里的 getToolCalls() = "
                + (toolCalls == null ? "null" : toolCalls.size() + " 条")
                + (toolCalls == null || toolCalls.isEmpty() ? "  → 方案 A/A′ 只能靠装饰器拿轨迹"
                        : "  → " + toolCalls));
        report(TAG + " (a) 最终回答 = " + response.getResult().getOutput().getText());
        report(TAG + " (b) 装饰器记录（工具名/入参/返回值/history 条数/线程）:");
        recorder.entries.forEach(entry -> report(TAG + "     " + entry));
        report(TAG + " (b) 结论提示：history>0 表示 ToolContext 里能看到之前的工具响应（缺的那半截可能在这里）");
    }

    @Test
    void concurrentTracesAreIsolated() throws Exception {
        ToolRecorder recorderA = new ToolRecorder("A");
        ToolRecorder recorderB = new ToolRecorder("B");
        ProbeTools toolsA = new ProbeTools();
        ProbeTools toolsB = new ProbeTools();
        ChatClient client = ChatClient.builder(chatModel()).build();
        CountDownLatch start = new CountDownLatch(1);

        Thread threadA = new Thread(() -> runOne(client, recorderA, toolsA, "request-A", start), "probe-A");
        Thread threadB = new Thread(() -> runOne(client, recorderB, toolsB, "request-B", start), "probe-B");
        threadA.start();
        threadB.start();
        start.countDown();          // 尽量让两个请求同时发起
        threadA.join();
        threadB.join();

        boolean isolated = recorderA.entries.stream().allMatch(e -> e.contains("request-A") || !e.contains("request-"))
                && recorderB.entries.stream().allMatch(e -> e.contains("request-B") || !e.contains("request-"));
        report(TAG + " (c) A 的轨迹条数=" + recorderA.entries.size() + "，B 的轨迹条数=" + recorderB.entries.size());
        recorderA.entries.forEach(e -> report(TAG + "     A| " + e));
        recorderB.entries.forEach(e -> report(TAG + "     B| " + e));
        report(TAG + " (c) 隔离结论 = " + (isolated ? "隔离（各自只看到自己的入参）" : "疑似串线，需要改回显式传参"));
    }

    private static void runOne(ChatClient client, ToolRecorder recorder, ProbeTools tools, String marker,
                               CountDownLatch start) {
        try {
            start.await();
            client.prompt()
                    .user("请调用 probe_echo 工具，把字符串 " + marker + " 原样返回；拿到结果后只回答工具返回的内容。")
                    .toolCallbacks(recorder.wrap(tools))
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            recorder.entries.add("调用失败: " + e);
        }
    }

    /** 会被模型调用的探针工具。 */
    static class ProbeTools {

        @Tool(name = "probe_echo", description = "把输入原样返回，用于测试工具调用链路")
        public String echo(String input) {
            return "echo:" + input;
        }
    }

    /**
     * 轨迹装饰器：包住框架生成的 ToolCallback，记录工具名/入参/返回值/耗时，
     * 并读取 {@link ToolContext#getToolCallHistory()} 的条数——不用裸 ThreadLocal，轨迹随调用上下文走。
     */
    static class ToolRecorder {

        private final String label;
        final List<String> entries = Collections.synchronizedList(new ArrayList<>());

        ToolRecorder(String label) {
            this.label = label;
        }

        List<ToolCallback> wrap(Object tools) {
            List<ToolCallback> callbacks = new ArrayList<>();
            for (ToolCallback delegate : MethodToolCallbackProvider.builder().toolObjects(tools).build()
                    .getToolCallbacks()) {
                callbacks.add(new RecordingToolCallback(delegate, entries, label));
            }
            return callbacks;
        }
    }

    record RecordingToolCallback(ToolCallback delegate, List<String> entries, String label) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String arguments) {
            return call(arguments, null);
        }

        @Override
        public String call(String arguments, ToolContext context) {
            long start = System.currentTimeMillis();
            String result = context == null ? delegate.call(arguments) : delegate.call(arguments, context);
            // historySize = -1 表示"框架没传 ToolContext"——2026-10-05 实测就是这样（默认走单参 call(String)），
            // 所以不能指望从 ToolContext 里读工具历史，轨迹由本装饰器的 entries 负责。
            int historySize = -1;
            if (context != null) {
                List<Message> history = context.getToolCallHistory();
                historySize = history == null ? -1 : history.size();
            }
            entries.add("[" + label + "] name=" + delegate.getToolDefinition().name()
                    + " args=" + arguments + " -> " + result
                    + " | history=" + historySize
                    + " | thread=" + Thread.currentThread().getName()
                    + " | ms=" + (System.currentTimeMillis() - start));
            return result;
        }
    }

    private static ChatModel chatModel() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(60_000);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).temperature(0.0).maxTokens(256).build())
                .build();
    }

    private static Map<String, String> readDotEnv() throws IOException {
        Map<String, String> values = new HashMap<>();
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            return values;
        }
        for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                continue;
            }
            values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return values;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
