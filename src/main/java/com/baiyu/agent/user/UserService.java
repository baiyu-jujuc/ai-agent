package com.baiyu.agent.user;

import com.baiyu.agent.user.entity.AppUser;
import com.baiyu.agent.user.repository.AppUserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final AppUserRepository userRepo;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public AppUser register(String username, String rawPassword) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        if (userRepo.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        AppUser user = new AppUser(username, passwordEncoder.encode(rawPassword));
        return userRepo.save(user);
    }

    public AppUser authenticate(String username, String rawPassword) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return user;
    }

    public AppUser findById(String userId) {
        return userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }
}
