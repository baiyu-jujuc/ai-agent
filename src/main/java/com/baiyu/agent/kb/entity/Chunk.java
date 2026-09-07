package com.baiyu.agent.kb.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_chunk")
public class Chunk {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "version_id", nullable = false)
    private String versionId;

    @Column(name = "space_id", nullable = false)
    private String spaceId;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(name = "chunk_index")
    private int chunkIndex;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(length = 500)
    private String heading;

    // disabled = old version, logically deleted
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public Chunk() {}

    public Chunk(String versionId, String spaceId, String documentId, String content, int chunkIndex) {
        this.versionId = versionId;
        this.spaceId = spaceId;
        this.documentId = documentId;
        this.content = content;
        this.chunkIndex = chunkIndex;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getSpaceId() { return spaceId; }
    public void setSpaceId(String spaceId) { this.spaceId = spaceId; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public String getHeading() { return heading; }
    public void setHeading(String heading) { this.heading = heading; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
