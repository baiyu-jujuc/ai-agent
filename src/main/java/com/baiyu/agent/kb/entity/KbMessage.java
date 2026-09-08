package com.baiyu.agent.kb.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_message")
public class KbMessage {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "space_id", nullable = false)
    private String spaceId;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(length = 20)
    private String confidence;

    @Column(name = "top_score")
    private Double topScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    public KbMessage() {}

    public KbMessage(String spaceId, String conversationId, String messageId, String userId,
                     String role, String content, String confidence, Double topScore) {
        this.spaceId = spaceId;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.confidence = confidence;
        this.topScore = topScore;
    }

    public String getId() { return id; }
    public String getSpaceId() { return spaceId; }
    public String getConversationId() { return conversationId; }
    public String getMessageId() { return messageId; }
    public String getUserId() { return userId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getConfidence() { return confidence; }
    public Double getTopScore() { return topScore; }
    public Instant getCreatedAt() { return createdAt; }
}
