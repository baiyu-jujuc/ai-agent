package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.Tool;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTool implements Tool {

    @Override
    public String getName() { return "time"; }

    @Override
    public String getDescription() {
        return "Returns the current date and time. No input required.";
    }

    @Override
    public String execute(String input) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return "Current time: " + now.format(formatter);
    }
}
