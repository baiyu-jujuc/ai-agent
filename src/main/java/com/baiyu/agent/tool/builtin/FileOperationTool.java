package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class FileOperationTool implements Tool {

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getDescription() {
        return "文件操作工具。支持读取和写入文件。输入格式: read:<路径> 或 write:<路径>:<内容>";
    }

    @Override
    public String execute(String input) {
        String cmd = input.trim();
        if (cmd.startsWith("read:")) {
            return readFile(cmd.substring(5).trim());
        } else if (cmd.startsWith("write:")) {
            String rest = cmd.substring(6);
            int colonIdx = rest.indexOf(':');
            if (colonIdx < 0) {
                return "写入格式错误，请使用 write:<路径>:<内容>";
            }
            String path = rest.substring(0, colonIdx).trim();
            String content = rest.substring(colonIdx + 1);
            return writeFile(path, content);
        } else if (cmd.startsWith("list:")) {
            return listFiles(cmd.substring(5).trim());
        }
        return "未知操作。支持: read:<路径>, write:<路径>:<内容>, list:<路径>";
    }

    private String readFile(String pathStr) {
        try {
            Path path = Path.of(pathStr);
            if (!Files.exists(path)) {
                return "文件不存在: " + pathStr;
            }
            List<String> lines = Files.readAllLines(path);
            String content = String.join("\n", lines);
            if (content.length() > 5000) {
                return content.substring(0, 5000) + "\n... (截断，共 " + content.length() + " 字符)";
            }
            return content;
        } catch (IOException e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    private String writeFile(String pathStr, String content) {
        try {
            Path path = Path.of(pathStr);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "文件写入成功: " + pathStr + " (" + content.length() + " 字符)";
        } catch (IOException e) {
            return "写入文件失败: " + e.getMessage();
        }
    }

    private String listFiles(String pathStr) {
        try {
            Path path = pathStr.isEmpty() ? Path.of(".") : Path.of(pathStr);
            if (!Files.isDirectory(path)) {
                return "不是目录: " + pathStr;
            }
            List<String> entries = Files.list(path)
                    .map(p -> {
                        String prefix = Files.isDirectory(p) ? "[DIR]  " : "[FILE] ";
                        return prefix + p.getFileName().toString();
                    })
                    .collect(Collectors.toList());
            if (entries.isEmpty()) {
                return "目录为空: " + pathStr;
            }
            return "目录内容 (" + pathStr + "):\n" + String.join("\n", entries);
        } catch (IOException e) {
            return "列目录失败: " + e.getMessage();
        }
    }
}
