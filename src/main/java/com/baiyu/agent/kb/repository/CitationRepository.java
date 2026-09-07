package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.Citation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CitationRepository extends JpaRepository<Citation, String> {
    List<Citation> findByMessageId(String messageId);
    List<Citation> findBySpaceId(String spaceId);
}
