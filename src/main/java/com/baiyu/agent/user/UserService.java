package com.baiyu.agent.user;

import com.baiyu.agent.user.entity.AppUser;
import com.baiyu.agent.user.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final AppUserRepository userRepo;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Set<String> adminUsernames;

    public UserService(AppUserRepository userRepo,
                       @Value("${agent.security.admin-usernames:}") String adminUsernames) {
        this.userRepo = userRepo;
        // 名单里的用户名注册后直接是 admin 角色；默认空，避免出现"谁先注册谁管理员"
        this.adminUsernames = Arrays.stream(adminUsernames.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
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
        if (adminUsernames.contains(username.toLowerCase(Locale.ROOT))) {
            user.setRole("admin");
            log.info("用户 {} 在管理员名单内，注册为 admin 角色", username);
        }
        return userRepo.save(user);
    }

    /**
     * 引导管理员：只在配置了用户名与密码、且该用户不存在时创建。
     *
     * <p>刻意<b>不</b>自动给已存在的同名用户提权：那等于"改一下环境变量就能提权"，
     * 是比越权更隐蔽的坑。需要提权就明确地改数据或换个用户名。
     */
    @Transactional
    public Optional<AppUser> ensureBootstrapAdmin(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            return Optional.empty();
        }
        Optional<AppUser> existing = userRepo.findByUsername(username);
        if (existing.isPresent()) {
            AppUser user = existing.get();
            if (!"admin".equalsIgnoreCase(user.getRole())) {
                log.warn("引导管理员用户名 {} 已存在且角色是 {}，不会自动提权；请手工调整角色或改用其它用户名",
                        username, user.getRole());
            }
            return Optional.empty();
        }
        if (rawPassword.length() < 8) {
            throw new IllegalArgumentException("引导管理员密码至少 8 位");
        }
        AppUser admin = new AppUser(username, passwordEncoder.encode(rawPassword));
        admin.setRole("admin");
        log.info("已创建引导管理员账号：{}（密码不打印、不落库明文）", username);
        return Optional.of(userRepo.save(admin));
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
