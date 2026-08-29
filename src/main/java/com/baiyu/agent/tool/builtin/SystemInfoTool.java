package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.Tool;
import org.springframework.stereotype.Component;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Properties;

@Component
public class SystemInfoTool implements Tool {

    @Override
    public String getName() { return "systeminfo"; }

    @Override
    public String getDescription() {
        return "Returns system information including OS, Java version, and runtime stats.";
    }

    @Override
    public String execute(String input) {
        StringBuilder sb = new StringBuilder();
        Properties props = System.getProperties();

        sb.append("===== System Information =====\n");
        sb.append("OS: ").append(props.getProperty("os.name")).append(" ")
                .append(props.getProperty("os.version")).append(" (")
                .append(props.getProperty("os.arch")).append(")\n");
        sb.append("Java: ").append(props.getProperty("java.version")).append("\n");
        sb.append("User: ").append(props.getProperty("user.name")).append("\n");
        sb.append("Working Dir: ").append(props.getProperty("user.dir")).append("\n");

        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        sb.append("JVM Uptime: ").append(runtime.getUptime() / 1000).append(" seconds\n");

        Runtime r = Runtime.getRuntime();
        sb.append("Memory: Used ").append((r.totalMemory() - r.freeMemory()) / 1048576)
                .append("MB / Total ").append(r.totalMemory() / 1048576)
                .append("MB / Max ").append(r.maxMemory() / 1048576).append("MB\n");

        sb.append("CPU Cores: ").append(r.availableProcessors()).append("\n");

        return sb.toString();
    }
}
