package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.KbMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbMessageRepository extends JpaRepository<KbMessage, String> {
    List<KbMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    Optional<KbMessage> findByMessageId(String messageId);
    void deleteByConversationId(String conversationId);
}
