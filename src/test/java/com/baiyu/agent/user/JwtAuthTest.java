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
        userService = new UserService(userRepo);
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
}
