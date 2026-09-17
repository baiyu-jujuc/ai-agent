package com.baiyu.agent.api;

import com.baiyu.agent.eval.EvalReport;
import com.baiyu.agent.eval.EvalRunner;
import com.baiyu.agent.eval.EvalSetLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 评测跑批的管理接口。
 *
 * <p>跑批是异步的：一次 60 多条用例要几分钟，同步阻塞 HTTP 请求容易超时。
 * 触发后用 /api/admin/eval/status 看状态，跑完在 /api/admin/eval/reports 里看结果。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminEvalController {

    private static final Logger log = LoggerFactory.getLogger(AdminEvalController.class);
    private static final Path REPORT_DIR = Path.of("eval", "reports");

    private final EvalRunner evalRunner;
    private final EvalSetLoader evalSetLoader;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "eval-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Map<String, Object>> lastRun =
            new AtomicReference<>(Map.of("status", "never-run"));

    public AdminEvalController(EvalRunner evalRunner, EvalSetLoader evalSetLoader) {
        this.evalRunner = evalRunner;
        this.evalSetLoader = evalSetLoader;
    }

    @GetMapping("/eval/cases")
    public Map<String, Object> cases() throws Exception {
        List<com.baiyu.agent.eval.EvalCase> loaded = evalSetLoader.load(EvalSetLoader.DEFAULT_PATH);
        return Map.of("count", loaded.size(), "items", loaded);
    }

    @PostMapping("/eval/run")
    public Map<String, Object> run(@RequestParam String tag,
                                   @RequestParam(defaultValue = "0") int limit,
                                   @RequestParam(defaultValue = "true") boolean seed) {
        Map<String, Object> running = new LinkedHashMap<>();
        running.put("status", "running");
        running.put("tag", tag);
        running.put("limit", limit);
        running.put("startedAt", Instant.now().toString());
        lastRun.set(running);

        executor.submit(() -> {
            try {
                EvalReport report = evalRunner.run(tag, limit, seed);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("status", "finished");
                done.put("tag", tag);
                done.put("caseCount", report.caseCount());
                done.put("hitRateAt5", report.hitRateAt5());
                done.put("citationAccuracy", report.citationAccuracy());
                done.put("avgRelevance", report.avgRelevance());
                done.put("p95LatencyMs", report.p95LatencyMs());
                done.put("avgCostMicros", report.avgCostMicros());
                done.put("reportFile", "eval/reports/eval-report-" + tag + ".json");
                lastRun.set(done);
            } catch (Exception e) {
                log.error("评测跑批失败", e);
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("status", "failed");
                failed.put("tag", tag);
                failed.put("error", e.getMessage());
                lastRun.set(failed);
            }
        });
        return running;
    }

    @GetMapping("/eval/status")
    public Map<String, Object> status() {
        return lastRun.get();
    }

    @GetMapping("/eval/reports")
    public Map<String, Object> reports() throws Exception {
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(REPORT_DIR)) {
            try (Stream<Path> stream = Files.list(REPORT_DIR)) {
                stream.filter(path -> path.getFileName().toString().startsWith("eval-report-"))
                        .sorted()
                        .forEach(path -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("file", path.getFileName().toString());
                            entry.put("size", path.toFile().length());
                            files.add(entry);
                        });
            }
        }
        return Map.of("count", files.size(), "items", files);
    }
}
