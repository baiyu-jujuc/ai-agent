package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.Citation;
import com.baiyu.agent.kb.entity.Feedback;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/kb")
public class KbQaController {

    private final KbQaService qaService;

    public KbQaController(KbQaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping("/spaces/{spaceId}/ask")
    public Map<String, Object> ask(@PathVariable String spaceId,
                                   @RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String conversationId = request.getOrDefault("conversationId", spaceId + ":default");
        KbQaService.QaResult result = qaService.ask(spaceId, question, conversationId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("messageId", result.messageId());
        resp.put("answer", result.answer());
        resp.put("confidence", result.confidence());
        resp.put("topScore", result.topScore());
        resp.put("conversationId", result.conversationId());

        List<Map<String, Object>> citations = new ArrayList<>();
        for (Citation c : result.citations()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("citationId", c.getId());
            cm.put("documentId", c.getDocumentId());
            cm.put("versionId", c.getVersionId());
            cm.put("chunkId", c.getChunkId());
            cm.put("score", c.getScore());
            citations.add(cm);
        }
        resp.put("citations", citations);
        return resp;
    }

    @PostMapping("/feedback")
    public Map<String, Object> submitFeedback(@RequestBody Map<String, String> request) {
        String messageId = request.get("messageId");
        String spaceId = request.get("spaceId");
        String thumbs = request.get("thumbs");
        String reason = request.getOrDefault("reason", "");
        String correction = request.getOrDefault("correction", "");
        if (messageId == null || spaceId == null || thumbs == null) {
            throw new IllegalArgumentException("messageId, spaceId, thumbs 不能为空");
        }
        Feedback fb = qaService.submitFeedback(messageId, spaceId, thumbs, reason, correction);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("feedbackId", fb.getId());
        resp.put("status", "recorded");
        return resp;
    }

    @GetMapping("/spaces/{spaceId}/feedback")
    public List<Map<String, Object>> getFeedback(@PathVariable String spaceId) {
        return qaService.getFeedback(spaceId).stream()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("feedbackId", f.getId());
                    m.put("messageId", f.getMessageId());
                    m.put("thumbs", f.getThumbs());
                    m.put("reason", f.getReason());
                    m.put("correction", f.getCorrection());
                    m.put("createdAt", f.getCreatedAt() != null ? f.getCreatedAt().toString() : null);
                    return m;
                })
                .toList();
    }

    @GetMapping("/messages/{messageId}/citations")
    public List<Map<String, Object>> getCitations(@PathVariable String messageId) {
        return qaService.getCitations(messageId).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("citationId", c.getId());
                    m.put("documentId", c.getDocumentId());
                    m.put("versionId", c.getVersionId());
                    m.put("chunkId", c.getChunkId());
                    m.put("score", c.getScore());
                    return m;
                })
                .toList();
    }
}
