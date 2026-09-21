package com.baiyu.agent.kb;

import com.baiyu.agent.gateway.CallScene;
import com.baiyu.agent.gateway.GatewayProperties;
import com.baiyu.agent.gateway.GatewayRequest;
import com.baiyu.agent.gateway.GatewayResponse;
import com.baiyu.agent.gateway.ModelGateway;
import com.baiyu.agent.gateway.PromptTemplateService;
import com.baiyu.agent.gateway.repository.PromptTemplateRepository;
import com.baiyu.agent.kb.entity.Chunk;
import com.baiyu.agent.kb.entity.KbMessage;
import com.baiyu.agent.kb.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class KbQaServiceTest {

    private KbQaService qaService;
    private KnowledgeBaseService kbService;
    private CitationRepository citationRepo;
    private FeedbackRepository feedbackRepo;
    private KbMessageRepository messageRepo;
    private ModelGateway modelGateway;

    @BeforeEach
    void setUp() {
        kbService = mock(KnowledgeBaseService.class);
        citationRepo = mock(CitationRepository.class);
        feedbackRepo = mock(FeedbackRepository.class);
        messageRepo = mock(KbMessageRepository.class);
        modelGateway = mock(ModelGateway.class);

        when(citationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feedbackRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(modelGateway.call(any(GatewayRequest.class)))
                .thenReturn(gatewayResponse("Java virtual threads are lightweight [1]"));

        // Prompt 模板服务用"内置模板"模式，避免单测依赖数据库
        GatewayProperties gatewayProperties = new GatewayProperties();
        gatewayProperties.setPromptStoreEnabled(false);
        PromptTemplateService promptTemplateService =
                new PromptTemplateService(mock(PromptTemplateRepository.class), gatewayProperties);
        qaService = new KbQaService(kbService, citationRepo, feedbackRepo, messageRepo,
                modelGateway, promptTemplateService);
    }

    @Test
    void askReturnsAnswerWithCitationsAndConfidence() {
        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "Java virtual threads are lightweight concurrency units", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");
        Chunk c2 = new Chunk("ver-001", "space-001", "doc-001", "Virtual threads simplify async programming", 1);
        ReflectionTestUtils.setField(c2, "id", "chunk-002");

        when(kbService.searchChunks("space-001", "What are virtual threads?", 5))
                .thenReturn(List.of(c1, c2));

        KbQaService.QaResult result = qaService.ask("space-001", "What are virtual threads?", "space-001:default", "user-001");

        assertNotNull(result.messageId());
        assertFalse(result.answer().isBlank());
        assertEquals(2, result.citations().size());
        assertNotNull(result.confidence());
        assertTrue(List.of("high", "medium", "low").contains(result.confidence()));
    }

    @Test
    void askWithNoChunksReturnsLowConfidence() {
        when(kbService.searchChunks(any(), any(), anyInt()))
                .thenReturn(List.of());

        KbQaService.QaResult result = qaService.ask("space-001", "unknown topic", "conv-1", "user-001");

        assertEquals("low", result.confidence());
        assertTrue(result.citations().isEmpty());
        verify(citationRepo, never()).save(any());
    }

    @Test
    void askEmptyQuestionFails() {
        assertThrows(IllegalArgumentException.class,
                () -> qaService.ask("space-001", "", "conv-1", "user-001"));
    }

    @Test
    void askEmptySpaceIdFails() {
        assertThrows(IllegalArgumentException.class,
                () -> qaService.ask("", "question", "conv-1", "user-001"));
    }

    @Test
    void askEmptyUserIdFails() {
        assertThrows(IllegalArgumentException.class,
                () -> qaService.ask("space-001", "question", "conv-1", ""));
    }

    @Test
    void submitFeedbackValidatesThumbs() {
        assertThrows(IllegalArgumentException.class,
                () -> qaService.submitFeedback("msg-1", "space-1", "sideways", "", ""));
    }

    @Test
    void submitFeedbackUpRecords() {
        var fb = qaService.submitFeedback("msg-1", "space-1", "up", "good answer", null);
        assertNotNull(fb);
        assertEquals("up", fb.getThumbs());
        verify(feedbackRepo).save(any());
    }

    @Test
    void submitFeedbackDownWithCorrection() {
        var fb = qaService.submitFeedback("msg-1", "space-1", "down", "wrong", "correct answer");
        assertEquals("down", fb.getThumbs());
        assertEquals("correct answer", fb.getCorrection());
    }

    @Test
    void submitFeedbackCommentWithReasonAndCorrection() {
        var fb = qaService.submitFeedback("msg-1", "space-1", "comment", "答案不完整", "应补充退款时限");
        assertEquals("comment", fb.getThumbs());
        assertEquals("答案不完整", fb.getReason());
        assertEquals("应补充退款时限", fb.getCorrection());
    }

    @Test
    void askSavesUserAndAssistantMessages() {
        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "Java virtual threads are lightweight concurrency units", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");

        when(kbService.searchChunks("space-001", "What are virtual threads?", 5))
                .thenReturn(List.of(c1));

        KbQaService.QaResult result = qaService.ask("space-001", "What are virtual threads?", "conv-msg-test", "user-001");

        // Verify two messages saved: user + assistant
        verify(messageRepo, times(2)).save(any(KbMessage.class));

        // Verify user message saved before retrieval
        verify(messageRepo).save(argThat(msg ->
                "user".equals(msg.getRole()) &&
                "What are virtual threads?".equals(msg.getContent()) &&
                "user-001".equals(msg.getUserId()) &&
                "space-001".equals(msg.getSpaceId()) &&
                "conv-msg-test".equals(msg.getConversationId()) &&
                msg.getConfidence() == null &&
                msg.getTopScore() == null
        ));

        // Verify assistant message saved with confidence and topScore
        verify(messageRepo).save(argThat(msg ->
                "assistant".equals(msg.getRole()) &&
                "Java virtual threads are lightweight [1]".equals(msg.getContent()) &&
                "user-001".equals(msg.getUserId()) &&
                "space-001".equals(msg.getSpaceId()) &&
                "conv-msg-test".equals(msg.getConversationId()) &&
                msg.getConfidence() != null &&
                msg.getTopScore() != null &&
                msg.getMessageId() != null
        ));
    }

    @Test
    void askWithNoChunksStillSavesBothMessages() {
        when(kbService.searchChunks(any(), any(), anyInt()))
                .thenReturn(List.of());

        KbQaService.QaResult result = qaService.ask("space-001", "unknown topic", "conv-empty", "user-001");

        // Verify two messages saved even when no chunks found
        verify(messageRepo, times(2)).save(any(KbMessage.class));

        // Verify assistant message has low confidence and 0.0 topScore
        verify(messageRepo).save(argThat(msg ->
                "assistant".equals(msg.getRole()) &&
                "low".equals(msg.getConfidence()) &&
                msg.getTopScore() == 0.0
        ));
    }

    @Test
    void getMessagesReturnsOrderedList() {
        KbMessage msg1 = new KbMessage("space-001", "conv-1", "msg-1", "user-001",
                "user", "Hello", null, null);
        KbMessage msg2 = new KbMessage("space-001", "conv-1", "msg-2", "user-001",
                "assistant", "Hi there", "high", 0.8);

        when(messageRepo.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(msg1, msg2));

        List<KbMessage> messages = qaService.getMessages("conv-1");

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("Hi there", messages.get(1).getContent());
        assertEquals("high", messages.get(1).getConfidence());
    }

    @Test
    void assistantMessageIdMatchesCitationMessageId() {
        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "Java virtual threads are lightweight", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");

        when(kbService.searchChunks("space-001", "question?", 5))
                .thenReturn(List.of(c1));

        KbQaService.QaResult result = qaService.ask("space-001", "question?", "conv-cit", "user-001");

        // The result messageId should match the assistant message and citations
        String resultMessageId = result.messageId();
        assertNotNull(resultMessageId);

        // Verify citation uses the same messageId as the result
        verify(citationRepo).save(argThat(cit ->
                resultMessageId.equals(((com.baiyu.agent.kb.entity.Citation) cit).getMessageId())
        ));
    }

    // P3-8 #4: Verify multi-turn conversation history is read and included in the prompt
    @Test
    void askIncludesConversationHistoryInPrompt() {
        KbMessage priorUser = new KbMessage("space-001", "conv-multi", "msg-prior-user", "user-001",
                "user", "本文档中的部署命令是什么", null, null);
        KbMessage priorAssistant = new KbMessage("space-001", "conv-multi", "msg-prior-assistant", "user-001",
                "assistant", "部署命令是 mvn spring-boot:run", "high", 0.85);

        when(messageRepo.findByConversationIdOrderByCreatedAtAsc("conv-multi"))
                .thenReturn(List.of(priorUser, priorAssistant));

        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "deploy with mvn spring-boot:run on port 8080", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");
        when(kbService.searchChunks("space-001", "启动后应该访问哪个端口", 5))
                .thenReturn(List.of(c1));

        qaService.ask("space-001", "启动后应该访问哪个端口", "conv-multi", "user-001");

        ArgumentCaptor<GatewayRequest> promptCaptor = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(modelGateway).call(promptCaptor.capture());

        String prompt = promptCaptor.getValue().userPrompt();
        assertTrue(prompt.contains("对话历史"), "Prompt should contain conversation history section");
        assertTrue(prompt.contains("部署命令"), "Prompt should contain prior user message content");
        assertTrue(prompt.contains("mvn spring-boot:run"), "Prompt should contain prior assistant response");
    }

    // P3-8 #5 改造后：KbQaService 只负责把客户端 Key 透传给网关，
    // "到底用不用这个 Key"由网关按 allow-client-model-key 决定（覆盖在 ModelGatewayImplTest）
    @Test
    void modelApiKeyForwardedToGateway() {
        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "some content", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");
        when(kbService.searchChunks(any(), any(), anyInt()))
                .thenReturn(List.of(c1));

        qaService.ask("space-001", "question", "conv-key-test", "user-001", "sk-user-provided-key");

        ArgumentCaptor<GatewayRequest> captor = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(modelGateway).call(captor.capture());
        assertEquals("sk-user-provided-key", captor.getValue().clientApiKey());
    }

    // 计量与成本按场景/空间/会话分组，这几个维度漏一个就没法算账
    @Test
    void gatewayRequestCarriesSceneAndIdentifiers() {
        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "some content", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");
        when(kbService.searchChunks(any(), any(), anyInt()))
                .thenReturn(List.of(c1));

        qaService.ask("space-001", "question", "conv-meta", "user-001");

        ArgumentCaptor<GatewayRequest> captor = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(modelGateway).call(captor.capture());
        GatewayRequest request = captor.getValue();
        assertEquals(CallScene.KB_QA, request.scene());
        assertEquals("space-001", request.spaceId());
        assertEquals("user-001", request.userId());
        assertEquals("conv-meta", request.conversationId());
    }

    private static GatewayResponse gatewayResponse(String content) {
        return new GatewayResponse(content, "deepseek-test", GatewayResponse.ROUTE_PRIMARY, false,
                "PROVIDER", 12, 8, 0L, 5L, null, null);
    }

    // P3-final: history must keep the most recent 20 messages, not the first 20
    @Test
    void askKeepsMostRecentTwentyHistoryMessages() {
        List<KbMessage> allHistory = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            allHistory.add(new KbMessage(
                    "space-001", "conv-history", "history-" + i, "user-001",
                    "user", "old question " + i, null, null));
            allHistory.add(new KbMessage(
                    "space-001", "conv-history", "history-answer-" + i, "user-001",
                    "assistant", "old answer " + i, "medium", 0.5));
        }
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc("conv-history"))
                .thenReturn(allHistory);

        Chunk chunk = new Chunk("ver-001", "space-001", "doc-001", "latest content", 0);
        ReflectionTestUtils.setField(chunk, "id", "chunk-001");
        when(kbService.searchChunks("space-001", "latest question", 5))
                .thenReturn(List.of(chunk));

        qaService.ask("space-001", "latest question", "conv-history", "user-001");

        ArgumentCaptor<GatewayRequest> promptCaptor = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(modelGateway).call(promptCaptor.capture());
        String prompt = promptCaptor.getValue().userPrompt();
        assertTrue(prompt.contains("old answer 24"), "Prompt should contain the newest history entry");
        assertTrue(prompt.contains("old question 24"), "Prompt should contain the newest user entry");
        assertFalse(prompt.contains("old question 0"), "Prompt should not contain the oldest history entry");
    }
}
