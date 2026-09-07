package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.Chunk;
import com.baiyu.agent.kb.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KbQaServiceTest {

    private KbQaService qaService;
    private KnowledgeBaseService kbService;
    private CitationRepository citationRepo;
    private FeedbackRepository feedbackRepo;
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        kbService = mock(KnowledgeBaseService.class);
        citationRepo = mock(CitationRepository.class);
        feedbackRepo = mock(FeedbackRepository.class);
        chatClient = mock(ChatClient.class);

        when(citationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feedbackRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        qaService = new KbQaService(kbService, citationRepo, feedbackRepo, chatClient);
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

        KbQaService.QaResult result = qaService.ask("space-001", "What are virtual threads?", "space-001:default");

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

        KbQaService.QaResult result = qaService.ask("space-001", "unknown topic", "conv-1");

        assertEquals("low", result.confidence());
        assertTrue(result.citations().isEmpty());
        verify(citationRepo, never()).save(any());
    }

    @Test
    void askEmptyQuestionFails() {
        assertThrows(IllegalArgumentException.class,
                () -> qaService.ask("space-001", "", "conv-1"));
    }

    @Test
    void askEmptySpaceIdFails() {
        assertThrows(IllegalArgumentException.class,
                () -> qaService.ask("", "question", "conv-1"));
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
}
