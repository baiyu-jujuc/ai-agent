package com.baiyu.agent.orchestrator;

public record TaskTrace(
        String agent,
        String inputSummary,
        String outputSummary,
        long durationMs,
        String status
) {
    public static TaskTrace success(String agent, String input, String output, long durationMs) {
        return new TaskTrace(agent, summarize(input), summarize(output), durationMs, "success");
    }

    public static TaskTrace error(String agent, String input, String error, long durationMs) {
        return new TaskTrace(agent, summarize(input), summarize(error), durationMs, "error");
    }

    private static String summarize(String text) {
        if (text == null) return "";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
