package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.KnowledgeSpace;
import com.baiyu.agent.kb.entity.SpaceMember;
import com.baiyu.agent.kb.repository.KnowledgeSpaceRepository;
import com.baiyu.agent.kb.repository.SpaceMemberRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PermissionService {

    private final KnowledgeSpaceRepository spaceRepo;
    private final SpaceMemberRepository memberRepo;

    public PermissionService(
            KnowledgeSpaceRepository spaceRepo,
            SpaceMemberRepository memberRepo) {
        this.spaceRepo = spaceRepo;
        this.memberRepo = memberRepo;
    }

    public boolean canRead(String spaceId, String userId) {
        KnowledgeSpace space = spaceRepo.findById(spaceId).orElse(null);
        if (space == null) return false;
        if ("public".equals(space.getVisibility())) return true;
        if (userId == null || userId.isBlank()) return false;
        Optional<SpaceMember> member = memberRepo.findBySpaceIdAndUserId(spaceId, userId);
        return member.isPresent();
    }

    public boolean canWrite(String spaceId, String userId) {
        if (userId == null || userId.isBlank()) return false;
        Optional<SpaceMember> member = memberRepo.findBySpaceIdAndUserId(spaceId, userId);
        if (member.isEmpty()) return false;
        String role = member.get().getRole();
        return "writer".equals(role) || "admin".equals(role);
    }

    public boolean canAdmin(String spaceId, String userId) {
        if (userId == null || userId.isBlank()) return false;
        Optional<SpaceMember> member = memberRepo.findBySpaceIdAndUserId(spaceId, userId);
        if (member.isEmpty()) return false;
        return "admin".equals(member.get().getRole());
    }

    public SpaceMember addMember(String spaceId, String userId, String role) {
        if (!isValidRole(role)) {
            throw new IllegalArgumentException("role 必须为 reader/writer/admin");
        }
        Optional<SpaceMember> existing = memberRepo.findBySpaceIdAndUserId(spaceId, userId);
        if (existing.isPresent()) {
            existing.get().setRole(role);
            return memberRepo.save(existing.get());
        }
        return memberRepo.save(new SpaceMember(spaceId, userId, role));
    }

    public void removeMember(String spaceId, String userId) {
        memberRepo.findBySpaceIdAndUserId(spaceId, userId)
                .ifPresent(memberRepo::delete);
    }

    public List<SpaceMember> listMembers(String spaceId) {
        return memberRepo.findBySpaceId(spaceId);
    }

    private boolean isValidRole(String role) {
        return "reader".equals(role) || "writer".equals(role) || "admin".equals(role);
    }
}
