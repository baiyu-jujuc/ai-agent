package com.baiyu.agent.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 用命令行触发评测跑批：
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments="--eval.run=true --eval.tag=post-upgrade --eval.limit=10"
 * </pre>
 *
 * 不触发时对启动没有任何影响（只是判断一个命令行参数）。
 */
@Component
public class EvalApplicationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalApplicationRunner.class);

    private final EvalRunner evalRunner;
    private final ConfigurableApplicationContext applicationContext;

    public EvalApplicationRunner(EvalRunner evalRunner, ConfigurableApplicationContext applicationContext) {
        this.evalRunner = evalRunner;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("eval.run")) {
            return;
        }
        String tag = args.getOptionValues("eval.tag") == null
                ? "current" : args.getOptionValues("eval.tag").get(0);
        int limit = parseLimit(args);
        boolean seed = !args.containsOption("eval.no-seed");
        log.info("开始离线评测跑批：tag={}, limit={}, seed={}", tag, limit, seed);
        int code = 0;
        try {
            evalRunner.run(tag, limit, seed);
        } catch (Exception e) {
            log.error("评测跑批失败", e);
            code = 1;
        }
        final int exitCode = code;
        // 跑批是一次性任务：跑完主动关掉 Spring 上下文。
        // 否则 @Scheduled 的线程池（非守护线程）会一直挂着，`mvn spring-boot:run` 永不退出。
        SpringApplication.exit(applicationContext, () -> exitCode);
    }

    private int parseLimit(ApplicationArguments args) {
        if (args.getOptionValues("eval.limit") == null) {
            return 0;
        }
        try {
            return Integer.parseInt(args.getOptionValues("eval.limit").get(0));
        } catch (NumberFormatException e) {
            log.warn("eval.limit 不是数字，按 0（全跑）处理");
            return 0;
        }
    }
}
