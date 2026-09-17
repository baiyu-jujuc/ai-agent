package com.baiyu.agent.user;

import com.baiyu.agent.user.entity.AppUser;
import com.baiyu.agent.user.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtAuthTest {

    private JwtUtil jwtUtil;
    private UserService userService;
    private AppUserRepository userRepo;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("test-secret-key-at-least-32-characters-long", 86400000);
        userRepo = mock(AppUserRepository.class);
        userService = new UserService(userRepo, "");
    }

    @Test
    void generateAndParseToken() {
        String token = jwtUtil.generateToken("user-1", "alice", "user");
        assertTrue(jwtUtil.isValid(token));
        assertEquals("user-1", jwtUtil.getUserId(token));
        assertEquals("alice", jwtUtil.getUsername(token));
    }

    @Test
    void invalidTokenRejected() {
        assertFalse(jwtUtil.isValid("invalid.token.here"));
        assertFalse(jwtUtil.isValid(""));
        assertFalse(jwtUtil.isValid(null));
    }

    @Test
    void registerUser() {
        when(userRepo.existsByUsername("bob")).thenReturn(false);
        when(userRepo.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AppUser user = userService.register("bob", "password123");
        assertNotNull(user.getUsername());
        assertNotEquals("password123", user.getPasswordHash());
    }

    @Test
    void registerDuplicateFails() {
        when(userRepo.existsByUsername("bob")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> userService.register("bob", "other"));
    }

    @Test
    void authenticateSuccess() {
        // Use a real BCrypt hash for "password123"
        String realHash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password123");
        AppUser mockUser = new AppUser("bob", realHash);
        when(userRepo.findByUsername("bob")).thenReturn(Optional.of(mockUser));
        AppUser user = userService.authenticate("bob", "password123");
        assertEquals("bob", user.getUsername());
    }

    @Test
    void authenticateWrongUser() {
        when(userRepo.findByUsername("nobody")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> userService.authenticate("nobody", "pass"));
    }

    @Test
    void shortPasswordRejected() {
        assertThrows(IllegalArgumentException.class, () -> userService.register("bob", "12"));
    }

    // 管理接口需要 ADMIN 角色：名单命中时才给 admin，默认名单为空
    @Test
    void usernameInAdminListGetsAdminRole() {
        when(userRepo.existsByUsername("boss")).thenReturn(false);
        when(userRepo.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser user = new UserService(userRepo, "boss, other").register("boss", "password123");

        assertEquals("admin", user.getRole());
    }

    @Test
    void usernameNotInAdminListGetsUserRole() {
        when(userRepo.existsByUsername("alice")).thenReturn(false);
        when(userRepo.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser user = new UserService(userRepo, "boss").register("alice", "password123");

        assertEquals("user", user.getRole());
    }

    @Test
    void bootstrapAdminCreatedOnlyWhenMissing() {
        when(userRepo.findByUsername("root-admin")).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser created = userService.ensureBootstrapAdmin("root-admin", "very-secret-password").orElseThrow();
        assertEquals("admin", created.getRole());
        assertNotEquals("very-secret-password", created.getPasswordHash());
    }

    @Test
    void bootstrapAdminDoesNotPromoteExistingUser() {
        AppUser existing = new AppUser("boss", "hash");
        when(userRepo.findByUsername("boss")).thenReturn(Optional.of(existing));

        assertTrue(userService.ensureBootstrapAdmin("boss", "very-secret-password").isEmpty(),
                "已存在的用户不能被自动提权");
        assertEquals("user", existing.getRole());
    }

    @Test
    void bootstrapAdminRejectedWhenNotConfigured() {
        assertTrue(userService.ensureBootstrapAdmin("", "").isEmpty());
        assertTrue(userService.ensureBootstrapAdmin(null, null).isEmpty());
    }
}
