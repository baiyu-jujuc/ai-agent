package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.SpaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpaceMemberRepository extends JpaRepository<SpaceMember, String> {
    List<SpaceMember> findBySpaceId(String spaceId);
    Optional<SpaceMember> findBySpaceIdAndUserId(String spaceId, String userId);
}
