package com.baiyu.agent.kb.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_feedback")
public class Feedback {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "space_id", nullable = false)
    private String spaceId;

    // up | down | comment
    @Column(nullable = false)
    private String thumbs;

    @Column(length = 2000)
    private String reason;

    @Column(length = 4000)
    private String correction;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public Feedback() {}

    public Feedback(String messageId, String spaceId, String thumbs, String reason, String correction) {
        this.messageId = messageId;
        this.spaceId = spaceId;
        this.thumbs = thumbs;
        this.reason = reason;
        this.correction = correction;
    }

    public String getId() { return id; }
    public String getMessageId() { return messageId; }
    public String getSpaceId() { return spaceId; }
    public String getThumbs() { return thumbs; }
    public String getReason() { return reason; }
    public String getCorrection() { return correction; }
    public Instant getCreatedAt() { return createdAt; }
}
