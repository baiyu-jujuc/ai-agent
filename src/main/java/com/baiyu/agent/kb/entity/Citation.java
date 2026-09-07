package com.baiyu.agent.kb.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_citation")
public class Citation {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "version_id")
    private String versionId;

    @Column(name = "chunk_id")
    private String chunkId;

    @Column(nullable = false)
    private double score;

    @Column(name = "space_id", nullable = false)
    private String spaceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public Citation() {}

    public Citation(String messageId, String spaceId, String documentId, String versionId, String chunkId, double score) {
        this.messageId = messageId;
        this.spaceId = spaceId;
        this.documentId = documentId;
        this.versionId = versionId;
        this.chunkId = chunkId;
        this.score = score;
    }

    public String getId() { return id; }
    public String getMessageId() { return messageId; }
    public String getDocumentId() { return documentId; }
    public String getVersionId() { return versionId; }
    public String getChunkId() { return chunkId; }
    public double getScore() { return score; }
    public String getSpaceId() { return spaceId; }
    public Instant getCreatedAt() { return createdAt; }
}
