package com.baiyu.agent.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时按配置创建引导管理员（幂等）。
 *
 * <p>为什么需要它：`/api/admin/**` 现在要求 ADMIN 角色，
 * 而注册接口只发 user 角色——没有引导管理员，管理接口就等于"永远没人能访问"。
 *
 * <p>没配置时只打一行日志说明，不会偷偷创建账号。
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final UserService userService;
    private final String username;
    private final String password;

    public AdminAccountInitializer(UserService userService,
                                   @Value("${agent.security.bootstrap-admin-username:}") String username,
                                   @Value("${agent.security.bootstrap-admin-password:}") String password) {
        this.userService = userService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.info("未配置引导管理员（BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD）："
                    + "/api/admin/** 需要 ADMIN 角色，可用 ADMIN_USERNAMES 名单或引导管理员来获得该角色");
            return;
        }
        try {
            userService.ensureBootstrapAdmin(username, password);
        } catch (Exception e) {
            log.warn("创建引导管理员失败（忽略，不影响启动）：{}", e.toString());
        }
    }
}
