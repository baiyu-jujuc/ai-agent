package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class FileOperationTool implements ToolComponent {

    @Value("${agent.security.file-access-dir:./workspace}")
    private String allowedBaseDir;

    @Tool(name = "file", description = "文件操作工具（沙箱模式）。支持读取和写入文件，仅限工作目录内。输入格式: read:<路径> 或 write:<路径>:<内容> 或 list:<路径>")
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

    private Path validatePath(String pathStr) {
        Path baseDir = Path.of(allowedBaseDir).toAbsolutePath().normalize();
        Path resolved = baseDir.resolve(pathStr).normalize();

        if (!resolved.startsWith(baseDir)) {
            throw new SecurityException("路径越权: 禁止访问工作目录之外的文件 (" + baseDir + ")");
        }
        return resolved;
    }

    private String readFile(String pathStr) {
        try {
            Path path = validatePath(pathStr);
            if (!Files.exists(path)) {
                return "文件不存在: " + pathStr;
            }
            List<String> lines = Files.readAllLines(path);
            String content = String.join("\n", lines);
            if (content.length() > 5000) {
                return content.substring(0, 5000) + "\n... (截断，共 " + content.length() + " 字符)";
            }
            return content;
        } catch (SecurityException e) {
            return "安全拒绝: " + e.getMessage();
        } catch (IOException e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    private String writeFile(String pathStr, String content) {
        try {
            Path path = validatePath(pathStr);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "文件写入成功: " + pathStr + " (" + content.length() + " 字符)";
        } catch (SecurityException e) {
            return "安全拒绝: " + e.getMessage();
        } catch (IOException e) {
            return "写入文件失败: " + e.getMessage();
        }
    }

    private String listFiles(String pathStr) {
        try {
            Path path = pathStr.isEmpty() ? Path.of(allowedBaseDir).toAbsolutePath().normalize() : validatePath(pathStr);
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
        } catch (SecurityException e) {
            return "安全拒绝: " + e.getMessage();
        } catch (IOException e) {
            return "列目录失败: " + e.getMessage();
        }
    }
}
