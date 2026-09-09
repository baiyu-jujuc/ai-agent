package com.baiyu.agent.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@ConditionalOnProperty(name = "legacy.rag.enabled", havingValue = "true")
public class RagController {

    private static final int MAX_CONTENT_LENGTH = 500_000;
    private static final int MAX_QUERY_LENGTH = 10_000;

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/documents")
    public Map<String, String> addDocument(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content 超过最大长度限制 (" + MAX_CONTENT_LENGTH + " 字符)");
        }
        String metadata = request.getOrDefault("metadata", "");
        String result = ragService.addDocument(content, metadata);
        return Map.of("status", "success", "message", result);
    }

    @PostMapping("/upload")
    public Map<String, String> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        try {
            String result = ragService.uploadAndIndex(file);
            return Map.of("status", "success", "message", result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            return Map.of("status", "error", "message", "文件处理失败");
        }
    }

    @GetMapping("/search")
    public List<String> search(@RequestParam String query,
                               @RequestParam(defaultValue = "5") int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query 超过最大长度限制");
        }
        return ragService.search(query, topK);
    }

    @PostMapping("/query")
    public Map<String, String> queryWithContext(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (question.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("question 超过最大长度限制");
        }
        String answer = ragService.queryWithContext(question);
        return Map.of("answer", answer);
    }
}
