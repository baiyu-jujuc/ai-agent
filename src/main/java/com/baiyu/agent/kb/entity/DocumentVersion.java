package com.baiyu.agent.kb.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_document_version")
public class DocumentVersion {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "space_id", nullable = false)
    private String spaceId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    // parsing | ready | failed
    @Column(name = "parse_status", nullable = false)
    private String parseStatus = "parsing";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "chunk_count")
    private int chunkCount;

    @Column(name = "rollback_from_version")
    private Integer rollbackFromVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public DocumentVersion() {}

    public DocumentVersion(String documentId, String spaceId, int versionNo) {
        this.documentId = documentId;
        this.spaceId = spaceId;
        this.versionNo = versionNo;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getSpaceId() { return spaceId; }
    public void setSpaceId(String spaceId) { this.spaceId = spaceId; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public Integer getRollbackFromVersion() { return rollbackFromVersion; }
    public void setRollbackFromVersion(Integer rollbackFromVersion) { this.rollbackFromVersion = rollbackFromVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
