package com.baiyu.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 读 {@code eval/eval_set.jsonl}：每行一个 JSON，逐行解析，坏行直接报错（不要静默跳过）。 */
@Component
public class EvalSetLoader {

    private static final Logger log = LoggerFactory.getLogger(EvalSetLoader.class);
    public static final Path DEFAULT_PATH = Path.of("eval", "eval_set.jsonl");

    private final ObjectMapper objectMapper;

    public EvalSetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvalCase> load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("评测集文件不存在: " + path.toAbsolutePath());
        }
        List<EvalCase> cases = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                cases.add(objectMapper.readValue(line, EvalCase.class));
            } catch (Exception e) {
                throw new IOException("第 " + (i + 1) + " 行无法解析为评测用例: " + e.getMessage(), e);
            }
        }
        log.info("评测集加载完成：{} 条（{}）", cases.size(), path.toAbsolutePath());
        return cases;
    }
}
