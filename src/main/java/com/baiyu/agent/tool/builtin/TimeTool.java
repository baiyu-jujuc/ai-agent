package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTool implements ToolComponent {

    @Tool(name = "time", description = "Returns the current date and time. No input required.")
    public String execute(String input) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return "Current time: " + now.format(formatter);
    }
}
