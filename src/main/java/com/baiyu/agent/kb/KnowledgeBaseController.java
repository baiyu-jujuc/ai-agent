package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.*;
import com.baiyu.agent.user.JwtAuthFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
        String userId = currentUserId();
        String name = request.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String description = request.getOrDefault("description", "");
        String visibility = request.getOrDefault("visibility", "team");
        String embeddingModel = request.get("embeddingModel");
        KnowledgeSpace space = kbService.createSpace(name, description, visibility, embeddingModel);
        // Creator auto-becomes admin
        permissionService.addMember(space.getId(), userId, "admin");
        return toSpaceMap(space);
    }

    @GetMapping("/spaces")
    public List<Map<String, Object>> listSpaces() {
        String userId = currentUserId();
        return kbService.listSpaces().stream()
                .filter(s -> permissionService.canRead(s.getId(), userId))
                .map(this::toSpaceMap)
                .toList();
    }

    @GetMapping("/spaces/{spaceId}")
    public Map<String, Object> getSpace(@PathVariable String spaceId) {
        String userId = currentUserId();
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该知识空间");
        }
        return toSpaceMap(kbService.getSpace(spaceId));
    }

    @GetMapping("/spaces/{spaceId}/documents")
    public List<Map<String, Object>> listDocuments(@PathVariable String spaceId) {
        String userId = currentUserId();
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该知识空间");
        }
        return kbService.listDocuments(spaceId).stream().map(this::toDocMap).toList();
    }

    @PostMapping("/spaces/{spaceId}/documents")
    public Map<String, Object> uploadDocument(@PathVariable String spaceId,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        String userId = currentUserId();
        if (!permissionService.canWrite(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权上传文档到该知识空间");
        }
        Document doc = kbService.uploadDocument(spaceId, file);
        return toDocMap(doc);
    }

    @GetMapping("/documents/{documentId}/versions")
    public List<Map<String, Object>> getVersions(@PathVariable String documentId) {
        String userId = currentUserId();
        String spaceId = kbService.getDocumentSpaceId(documentId);
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该文档");
        }
        return kbService.getDocumentVersions(documentId).stream().map(this::toVersionMap).toList();
    }

    @GetMapping("/documents/{documentId}/versions/active")
    public Map<String, Object> getActiveVersion(@PathVariable String documentId) {
        String userId = currentUserId();
        String spaceId = kbService.getDocumentSpaceId(documentId);
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该文档");
        }
        return toVersionMap(kbService.getActiveVersion(documentId));
    }

    @PostMapping("/documents/{documentId}/rollback/{versionNo}")
    public Map<String, Object> rollback(@PathVariable String documentId,
                                         @PathVariable int versionNo) {
        String userId = currentUserId();
        String spaceId = kbService.getDocumentSpaceId(documentId);
        if (!permissionService.canWrite(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权回滚文档");
        }
        return toVersionMap(kbService.rollbackToVersion(documentId, versionNo));
    }

    @GetMapping("/spaces/{spaceId}/search")
    public List<Map<String, Object>> search(@PathVariable String spaceId,
                                            @RequestParam String query,
                                            @RequestParam(defaultValue = "5") int topK) {
        String userId = currentUserId();
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权检索该知识空间");
        }
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

    @PostMapping("/spaces/{spaceId}/members")
    public Map<String, Object> addMember(@PathVariable String spaceId,
                                         @RequestBody Map<String, String> request) {
        String userId = currentUserId();
        if (!permissionService.canAdmin(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        String newUserId = request.get("userId");
        String role = request.getOrDefault("role", "reader");
        if (newUserId == null || newUserId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        var member = permissionService.addMember(spaceId, newUserId, role);
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
        String currentUserId = currentUserId();
        if (!permissionService.canAdmin(spaceId, currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        permissionService.removeMember(spaceId, userId);
        return Map.of("status", "removed");
    }

    @GetMapping("/spaces/{spaceId}/members")
    public List<Map<String, Object>> listMembers(@PathVariable String spaceId) {
        String userId = currentUserId();
        if (!permissionService.canRead(spaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该知识空间");
        }
        return permissionService.listMembers(spaceId).stream()
                .map(m -> {
                    Map<String, Object> member = new LinkedHashMap<>();
                    member.put("id", m.getId());
                    member.put("spaceId", m.getSpaceId());
                    member.put("userId", m.getUserId());
                    member.put("role", m.getRole());
                    member.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
                    return member;
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
        List<DocumentVersion> versions = kbService.getDocumentVersions(d.getId());
        DocumentVersion activeVersion = versions.stream().filter(DocumentVersion::isActive).findFirst().orElse(null);
        m.put("activeVersionNo", activeVersion != null ? activeVersion.getVersionNo() : null);
        m.put("versionCount", versions.size());
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
