package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final PermissionService permissionService;

    public KnowledgeBaseController(KnowledgeBaseService kbService, PermissionService permissionService) {
        this.kbService = kbService;
        this.permissionService = permissionService;
    }

    @PostMapping("/spaces")
    public Map<String, Object> createSpace(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String description = request.getOrDefault("description", "");
        String visibility = request.getOrDefault("visibility", "team");
        String embeddingModel = request.get("embeddingModel");
        KnowledgeSpace space = kbService.createSpace(name, description, visibility, embeddingModel);
        return toSpaceMap(space);
    }

    @GetMapping("/spaces")
    public List<Map<String, Object>> listSpaces() {
        return kbService.listSpaces().stream().map(this::toSpaceMap).toList();
    }

    @GetMapping("/spaces/{spaceId}")
    public Map<String, Object> getSpace(@PathVariable String spaceId) {
        return toSpaceMap(kbService.getSpace(spaceId));
    }

    @GetMapping("/spaces/{spaceId}/documents")
    public List<Map<String, Object>> listDocuments(@PathVariable String spaceId) {
        return kbService.listDocuments(spaceId).stream().map(this::toDocMap).toList();
    }

    @PostMapping("/spaces/{spaceId}/documents")
    public Map<String, Object> uploadDocument(@PathVariable String spaceId,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        Document doc = kbService.uploadDocument(spaceId, file);
        return toDocMap(doc);
    }

    @GetMapping("/documents/{documentId}/versions")
    public List<Map<String, Object>> getVersions(@PathVariable String documentId) {
        return kbService.getDocumentVersions(documentId).stream().map(this::toVersionMap).toList();
    }

    @GetMapping("/documents/{documentId}/versions/active")
    public Map<String, Object> getActiveVersion(@PathVariable String documentId) {
        return toVersionMap(kbService.getActiveVersion(documentId));
    }

    @PostMapping("/documents/{documentId}/rollback/{versionNo}")
    public Map<String, Object> rollback(@PathVariable String documentId,
                                         @PathVariable int versionNo) {
        return toVersionMap(kbService.rollbackToVersion(documentId, versionNo));
    }

    @GetMapping("/spaces/{spaceId}/search")
    public List<Map<String, Object>> search(@PathVariable String spaceId,
                                            @RequestParam String query,
                                            @RequestParam(defaultValue = "5") int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        return kbService.searchChunks(spaceId, query, topK).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chunkId", c.getId());
                    m.put("documentId", c.getDocumentId());
                    m.put("versionId", c.getVersionId());
                    m.put("content", c.getContent());
                    m.put("chunkIndex", c.getChunkIndex());
                    m.put("heading", c.getHeading());
                    m.put("pageNumber", c.getPageNumber());
                    return m;
                })
                .toList();
    }

    // M3: Space membership management

    @PostMapping("/spaces/{spaceId}/members")
    public Map<String, Object> addMember(@PathVariable String spaceId,
                                         @RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        String role = request.getOrDefault("role", "reader");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        var member = permissionService.addMember(spaceId, userId, role);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", member.getId());
        m.put("spaceId", member.getSpaceId());
        m.put("userId", member.getUserId());
        m.put("role", member.getRole());
        return m;
    }

    @DeleteMapping("/spaces/{spaceId}/members/{userId}")
    public Map<String, String> removeMember(@PathVariable String spaceId,
                                            @PathVariable String userId) {
        permissionService.removeMember(spaceId, userId);
        return Map.of("status", "removed");
    }

    @GetMapping("/spaces/{spaceId}/members")
    public List<Map<String, Object>> listMembers(@PathVariable String spaceId) {
        return java.util.Collections.emptyList();
    }

    private Map<String, Object> toSpaceMap(KnowledgeSpace s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("visibility", s.getVisibility());
        m.put("defaultPermission", s.getDefaultPermission());
        m.put("embeddingModel", s.getEmbeddingModel());
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toDocMap(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("spaceId", d.getSpaceId());
        m.put("filename", d.getFilename());
        m.put("mimeType", d.getMimeType());
        m.put("status", d.getStatus());
        m.put("creatorId", d.getCreatorId());
        m.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toVersionMap(DocumentVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("documentId", v.getDocumentId());
        m.put("versionNo", v.getVersionNo());
        m.put("contentHash", v.getContentHash());
        m.put("parseStatus", v.getParseStatus());
        m.put("active", v.isActive());
        m.put("chunkCount", v.getChunkCount());
        m.put("rollbackFromVersion", v.getRollbackFromVersion());
        m.put("createdAt", v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
        return m;
    }
}
