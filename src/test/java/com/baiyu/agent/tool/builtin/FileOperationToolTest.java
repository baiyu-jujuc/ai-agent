package com.baiyu.agent.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileOperationToolTest {

    @TempDir
    Path tempDir;

    private FileOperationTool fileTool;

    @BeforeEach
    void setUp() {
        fileTool = new FileOperationTool();
        org.springframework.test.util.ReflectionTestUtils.setField(
                fileTool, "allowedBaseDir", tempDir.toString());
    }

    @Test
    void writeAndReadFile() {
        fileTool.execute("write:test.txt:hello world");
        String result = fileTool.execute("read:test.txt");
        assertTrue(result.contains("hello world"), result);
    }

    @Test
    void pathTraversalBlocked() {
        String result = fileTool.execute("read:../../../etc/passwd");
        assertTrue(result.contains("安全拒绝") || result.contains("路径越权"), result);
    }

    @Test
    void absolutePathBlocked() {
        String result = fileTool.execute("read:/etc/passwd");
        assertTrue(result.contains("安全拒绝") || result.contains("路径越权"), result);
    }

    @Test
    void readNonExistentFile() {
        String result = fileTool.execute("read:nonexistent.txt");
        assertTrue(result.contains("文件不存在"), result);
    }

    @Test
    void listDirectory() {
        fileTool.execute("write:a.txt:content a");
        fileTool.execute("write:b.txt:content b");
        String result = fileTool.execute("list:");
        assertTrue(result.contains("a.txt"), result);
        assertTrue(result.contains("b.txt"), result);
    }

    @Test
    void listEmptyDirectory() {
        String result = fileTool.execute("list:");
        assertTrue(result.contains("目录为空") || result.contains("目录内容"), result);
    }

    @Test
    void longContentTruncated() {
        String longContent = "A".repeat(6000);
        fileTool.execute("write:long.txt:" + longContent);
        String result = fileTool.execute("read:long.txt");
        assertTrue(result.contains("截断"), result);
    }

    @Test
    void unknownCommand() {
        String result = fileTool.execute("delete:test.txt");
        assertTrue(result.contains("未知操作"), result);
    }

    @Test
    void writeFormatError() {
        String result = fileTool.execute("write:test.txt");
        assertTrue(result.contains("格式错误"), result);
    }
}
