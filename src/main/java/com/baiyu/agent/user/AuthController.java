package com.baiyu.agent.user;

import com.baiyu.agent.user.entity.AppUser;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    public AuthController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        AppUser user = userService.register(username, password);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername()
        );
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        AppUser user = userService.authenticate(username, password);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername()
        );
    }
}
