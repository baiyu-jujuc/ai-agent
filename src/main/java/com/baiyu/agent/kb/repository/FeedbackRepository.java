package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, String> {
    List<Feedback> findBySpaceId(String spaceId);
    List<Feedback> findByMessageId(String messageId);
}
