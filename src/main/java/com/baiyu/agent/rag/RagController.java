package com.baiyu.agent.rag;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/documents")
    public Map<String, String> addDocument(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String metadata = request.getOrDefault("metadata", "");
        String result = ragService.addDocument(content, metadata);
        return Map.of("status", "success", "message", result);
    }

    @PostMapping("/upload")
    public Map<String, String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            String result = ragService.uploadAndIndex(file);
            return Map.of("status", "success", "message", result);
        } catch (IOException e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @GetMapping("/search")
    public List<String> search(@RequestParam String query,
                               @RequestParam(defaultValue = "5") int topK) {
        return ragService.search(query, topK);
    }

    @PostMapping("/query")
    public Map<String, String> queryWithContext(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String answer = ragService.queryWithContext(question);
        return Map.of("answer", answer);
    }
}
