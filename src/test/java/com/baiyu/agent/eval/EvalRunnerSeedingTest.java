package com.baiyu.agent.eval;

import com.baiyu.agent.gateway.ModelGateway;
import com.baiyu.agent.gateway.repository.LlmUsageRecordRepository;
import com.baiyu.agent.kb.KbQaService;
import com.baiyu.agent.kb.KnowledgeBaseService;
import com.baiyu.agent.kb.entity.Document;
import com.baiyu.agent.kb.entity.DocumentVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测播种的回归测试。
 *
 * <p>背景：`hard-004`~`hard-008` 第一次跑全线 1.0 分，排查后发现不是应用的问题，
 * 而是播种逻辑用"文件名是否已存在"判断要不要灌文档——同名文档的第二个版本因此永远灌不进去。
 * 这两条用例把这个修复钉住。
 */
class EvalRunnerSeedingTest {

    private static final String SPACE = "space-x";
    private static final String V1 = "docs/demo-data/release-manual-v1/release-manual.md";
    private static final String V2 = "docs/demo-data/release-manual-v2/release-manual.md";

    private KnowledgeBaseService kbService;
    private EvalRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        kbService = mock(KnowledgeBaseService.class);
        runner = new EvalRunner(kbService,
                mock(KbQaService.class),
                mock(ModelGateway.class),
                mock(LlmUsageRecordRepository.class),
                mock(EvalSetLoader.class),
                new ObjectMapper());

        Document document = new Document(SPACE, "release-manual.md", "text/markdown");
        document.setId("doc-1");
        when(kbService.listDocuments(SPACE)).thenReturn(List.of(document));
        when(kbService.uploadDocument(eq(SPACE), any())).thenReturn(document);
    }

    @Test
    void uploadsNewVersionWhenSameFilenameHasDifferentContent() throws IOException {
        // 库里只有 1.0 版的内容（这正是线上第一次跑的状态）
        when(kbService.getDocumentVersions("doc-1"))
                .thenReturn(List.of(versionWithHash(EvalRunner.sha256Hex(Files.readAllBytes(Path.of(V1))))));

        runner.ensureDocument(SPACE, V2);

        verify(kbService).uploadDocument(eq(SPACE), any());
    }

    @Test
    void skipsUploadWhenSameContentAlreadyStored() throws IOException {
        when(kbService.getDocumentVersions("doc-1"))
                .thenReturn(List.of(versionWithHash(EvalRunner.sha256Hex(Files.readAllBytes(Path.of(V2))))));

        runner.ensureDocument(SPACE, V2);

        verify(kbService, never()).uploadDocument(any(), any());
    }

    @Test
    void uploadsWhenDocumentDoesNotExistYet() throws IOException {
        when(kbService.listDocuments(SPACE)).thenReturn(List.of());

        runner.ensureDocument(SPACE, V1);

        verify(kbService).uploadDocument(eq(SPACE), any());
    }

    private DocumentVersion versionWithHash(String hash) {
        DocumentVersion version = new DocumentVersion("doc-1", SPACE, 1);
        version.setContentHash(hash);
        return version;
    }
}
