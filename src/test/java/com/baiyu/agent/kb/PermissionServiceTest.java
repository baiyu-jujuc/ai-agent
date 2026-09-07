package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.KnowledgeSpace;
import com.baiyu.agent.kb.entity.SpaceMember;
import com.baiyu.agent.kb.repository.KnowledgeSpaceRepository;
import com.baiyu.agent.kb.repository.SpaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PermissionServiceTest {

    private PermissionService permissionService;
    private KnowledgeSpaceRepository spaceRepo;
    private SpaceMemberRepository memberRepo;

    @BeforeEach
    void setUp() {
        spaceRepo = mock(KnowledgeSpaceRepository.class);
        memberRepo = mock(SpaceMemberRepository.class);
        permissionService = new PermissionService(spaceRepo, memberRepo);

        when(spaceRepo.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void publicSpaceReadableByAnyone() {
        KnowledgeSpace space = new KnowledgeSpace("Public", "test");
        space.setVisibility("public");
        ReflectionTestUtils.setField(space, "id", "space-001");
        when(spaceRepo.findById("space-001")).thenReturn(Optional.of(space));

        assertTrue(permissionService.canRead("space-001", null));
        assertTrue(permissionService.canRead("space-001", "user-001"));
    }

    @Test
    void privateSpaceRequiresMembership() {
        KnowledgeSpace space = new KnowledgeSpace("Private", "test");
        space.setVisibility("team");
        ReflectionTestUtils.setField(space, "id", "space-001");
        when(spaceRepo.findById("space-001")).thenReturn(Optional.of(space));
        when(memberRepo.findBySpaceIdAndUserId("space-001", "user-001"))
                .thenReturn(Optional.empty());

        assertFalse(permissionService.canRead("space-001", "user-001"));
        assertFalse(permissionService.canRead("space-001", null));
    }

    @Test
    void memberCanRead() {
        KnowledgeSpace space = new KnowledgeSpace("Team", "test");
        space.setVisibility("team");
        ReflectionTestUtils.setField(space, "id", "space-001");
        when(spaceRepo.findById("space-001")).thenReturn(Optional.of(space));

        SpaceMember member = new SpaceMember("space-001", "user-001", "reader");
        when(memberRepo.findBySpaceIdAndUserId("space-001", "user-001"))
                .thenReturn(Optional.of(member));

        assertTrue(permissionService.canRead("space-001", "user-001"));
        assertFalse(permissionService.canWrite("space-001", "user-001"));
    }

    @Test
    void writerCanWrite() {
        KnowledgeSpace space = new KnowledgeSpace("Team", "test");
        space.setVisibility("team");
        ReflectionTestUtils.setField(space, "id", "space-001");
        when(spaceRepo.findById("space-001")).thenReturn(Optional.of(space));

        SpaceMember member = new SpaceMember("space-001", "user-001", "writer");
        when(memberRepo.findBySpaceIdAndUserId("space-001", "user-001"))
                .thenReturn(Optional.of(member));

        assertTrue(permissionService.canWrite("space-001", "user-001"));
        assertFalse(permissionService.canAdmin("space-001", "user-001"));
    }

    @Test
    void adminCanAdmin() {
        KnowledgeSpace space = new KnowledgeSpace("Team", "test");
        space.setVisibility("team");
        ReflectionTestUtils.setField(space, "id", "space-001");
        when(spaceRepo.findById("space-001")).thenReturn(Optional.of(space));

        SpaceMember member = new SpaceMember("space-001", "user-001", "admin");
        when(memberRepo.findBySpaceIdAndUserId("space-001", "user-001"))
                .thenReturn(Optional.of(member));

        assertTrue(permissionService.canAdmin("space-001", "user-001"));
        assertTrue(permissionService.canWrite("space-001", "user-001"));
        assertTrue(permissionService.canRead("space-001", "user-001"));
    }

    @Test
    void addMemberInvalidRoleFails() {
        assertThrows(IllegalArgumentException.class,
                () -> permissionService.addMember("space-001", "user-001", "superuser"));
    }

    @Test
    void addMemberUpdatesExistingRole() {
        SpaceMember existing = new SpaceMember("space-001", "user-001", "reader");
        when(memberRepo.findBySpaceIdAndUserId("space-001", "user-001"))
                .thenReturn(Optional.of(existing));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SpaceMember result = permissionService.addMember("space-001", "user-001", "admin");
        assertEquals("admin", result.getRole());
    }

    @Test
    void nonexistentSpaceReturnsFalse() {
        when(spaceRepo.findById("nonexistent")).thenReturn(Optional.empty());
        assertFalse(permissionService.canRead("nonexistent", "user-001"));
    }
}
