package com.baiyu.agent.kb.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_space_member")
public class SpaceMember {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "space_id", nullable = false)
    private String spaceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    // reader | writer | admin
    @Column(nullable = false)
    private String role = "reader";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public SpaceMember() {}

    public SpaceMember(String spaceId, String userId, String role) {
        this.spaceId = spaceId;
        this.userId = userId;
        this.role = role;
    }

    public String getId() { return id; }
    public String getSpaceId() { return spaceId; }
    public String getUserId() { return userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
}
