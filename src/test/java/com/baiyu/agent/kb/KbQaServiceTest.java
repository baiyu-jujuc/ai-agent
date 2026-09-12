package com.baiyu.agent.kb;

import com.baiyu.agent.config.ChatModelFactory;
import com.baiyu.agent.kb.entity.Chunk;
import com.baiyu.agent.kb.entity.KbMessage;
import com.baiyu.agent.kb.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
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
    private ChatClient chatClient;
    private ChatModelFactory chatModelFactory;

    @BeforeEach
    void setUp() {
        kbService = mock(KnowledgeBaseService.class);
        citationRepo = mock(CitationRepository.class);
        feedbackRepo = mock(FeedbackRepository.class);
        messageRepo = mock(KbMessageRepository.class);
        chatClient = mock(ChatClient.class);
        chatModelFactory = mock(ChatModelFactory.class);

        when(citationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feedbackRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        qaService = new KbQaService(kbService, citationRepo, feedbackRepo, messageRepo, chatClient, chatModelFactory, false);
    }

    @Test
    void askReturnsAnswerWithCitationsAndConfidence() {
        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "Java virtual threads are lightweight concurrency units", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");
        Chunk c2 = new Chunk("ver-001", "space-001", "doc-001", "Virtual threads simplify async programming", 1);
        ReflectionTestUtils.setField(c2, "id", "chunk-002");

        when(kbService.searchChunks("space-001", "What are virtual threads?", 5))
                .thenReturn(List.of(c1, c2));

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Java virtual threads are lightweight [1]");

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

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Java virtual threads are lightweight [1]");

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

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("answer");

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

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("启动后访问端口 8080 [1]");

        qaService.ask("space-001", "启动后应该访问哪个端口", "conv-multi", "user-001");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());

        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("对话历史"), "Prompt should contain conversation history section");
        assertTrue(prompt.contains("部署命令"), "Prompt should contain prior user message content");
        assertTrue(prompt.contains("mvn spring-boot:run"), "Prompt should contain prior assistant response");
    }

    // P3-8 #5: Verify model API key is ignored when allow-client-model-key=false
    @Test
    void modelApiKeyIgnoredWhenDisabled() {
        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "some content", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");
        when(kbService.searchChunks(any(), any(), anyInt()))
                .thenReturn(List.of(c1));

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("answer [1]");

        // allowClientModelKey=false (default in setUp)
        qaService.ask("space-001", "question", "conv-key-test", "user-001", "sk-user-provided-key");

        // chatModelFactory.createChatModel should never be called when disabled
        verify(chatModelFactory, never()).createChatModel(any());
    }

    // P3-8 #5: Verify model API key is used when allow-client-model-key=true
    @Test
    void modelApiKeyUsedWhenEnabled() {
        KbQaService enabledService = new KbQaService(
                kbService, citationRepo, feedbackRepo, messageRepo, chatClient, chatModelFactory, true);

        Chunk c1 = new Chunk("ver-001", "space-001", "doc-001", "some content", 0);
        ReflectionTestUtils.setField(c1, "id", "chunk-001");
        when(kbService.searchChunks(any(), any(), anyInt()))
                .thenReturn(List.of(c1));

        org.springframework.ai.chat.model.ChatModel perRequestModel = mock(org.springframework.ai.chat.model.ChatModel.class);
        when(chatModelFactory.createChatModel("sk-user-provided-key"))
                .thenReturn(perRequestModel);

        // When model key is provided and enabled, chatModelFactory should be called
        // The ChatClient.builder(perRequestModel).build() creates a real client,
        // but since perRequestModel is a mock, calling prompt() will return null → NPE
        // This is acceptable: we just verify chatModelFactory.createChatModel was called
        try {
            enabledService.ask("space-001", "question", "conv-key-enabled", "user-001", "sk-user-provided-key");
        } catch (Exception ignored) {
            // Expected: mock ChatModel can't actually process prompts
        }

        verify(chatModelFactory).createChatModel("sk-user-provided-key");
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

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("answer [1]");

        qaService.ask("space-001", "latest question", "conv-history", "user-001");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("old answer 24"), "Prompt should contain the newest history entry");
        assertTrue(prompt.contains("old question 24"), "Prompt should contain the newest user entry");
        assertFalse(prompt.contains("old question 0"), "Prompt should not contain the oldest history entry");
    }
}
