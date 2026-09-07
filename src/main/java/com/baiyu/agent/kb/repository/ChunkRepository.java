package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, String> {
    List<Chunk> findByVersionIdAndEnabledTrue(String versionId);
    List<Chunk> findByVersionId(String versionId);
    List<Chunk> findBySpaceIdAndEnabledTrue(String spaceId);
}
