package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.*;
import com.baiyu.agent.kb.repository.ChunkRepository;
import com.baiyu.agent.kb.repository.DocumentRepository;
import com.baiyu.agent.kb.repository.DocumentVersionRepository;
import com.baiyu.agent.user.JwtAuthFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/kb")
public class KbQaController {

    private final KbQaService qaService;
    private final PermissionService permissionService;
    private final DocumentRepository docRepo;
    private final DocumentVersionRepository versionRepo;
    private final ChunkRepository chunkRepo;

    public KbQaController(KbQaService qaService, PermissionService permissionService,
                          DocumentRepository docRepo, DocumentVersionRepository versionRepo,
                          ChunkRepository chunkRepo) {
        this.qaService = qaService;
        this.permissionService = permissionService;
        this.docRepo = docRepo;
        this.versionRepo = versionRepo;
        this.chunkRepo = chunkRepo;
    }

    @PostMapping("/spaces/{spaceId}/ask")
    public Map<String, Object> ask(@PathVariable String spaceId,
                                   @RequestBody Map<String, String> request,
                                   @RequestHeader(value = "X-Model-API-Key", required = false) String modelApiKey) {
        String userId = currentUserId();
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该知识空间");
        }
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String conversationId = request.getOrDefault("conversationId", spaceId + ":" + userId);
        KbQaService.QaResult result = qaService.ask(spaceId, question, conversationId, userId, modelApiKey);

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
            docRepo.findById(c.getDocumentId()).ifPresent(d -> cm.put("documentName", d.getFilename()));
            versionRepo.findById(c.getVersionId()).ifPresent(v -> cm.put("versionNo", v.getVersionNo()));
            chunkRepo.findById(c.getChunkId()).ifPresent(ch -> {
                cm.put("content", ch.getContent());
                if (ch.getHeading() != null) cm.put("heading", ch.getHeading());
            });
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
        String userId = currentUserId();
        String messageId = request.get("messageId");
        String spaceId = request.get("spaceId");
        String thumbs = request.get("thumbs");
        String reason = request.getOrDefault("reason", "");
        String correction = request.getOrDefault("correction", "");
        if (messageId == null || spaceId == null || thumbs == null) {
            throw new IllegalArgumentException("messageId, spaceId, thumbs 不能为空");
        }
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该知识空间");
        }
        Feedback fb = qaService.submitFeedback(messageId, spaceId, thumbs, reason, correction);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("feedbackId", fb.getId());
        resp.put("status", "recorded");
        return resp;
    }

    @GetMapping("/spaces/{spaceId}/feedback")
    public List<Map<String, Object>> getFeedback(@PathVariable String spaceId) {
        String userId = currentUserId();
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该知识空间");
        }
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

    @GetMapping("/spaces/{spaceId}/conversations/{conversationId}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable String spaceId,
                                                  @PathVariable String conversationId) {
        String userId = currentUserId();
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该知识空间");
        }
        return qaService.getMessages(conversationId).stream()
                .map(m -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("id", m.getId());
                    msg.put("messageId", m.getMessageId());
                    msg.put("role", m.getRole());
                    msg.put("content", m.getContent());
                    if (m.getConfidence() != null) msg.put("confidence", m.getConfidence());
                    if (m.getTopScore() != null) msg.put("topScore", m.getTopScore());
                    msg.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
                    return msg;
                })
                .toList();
    }

    private String currentUserId() {
        String userId = JwtAuthFilter.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }
}
