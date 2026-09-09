package com.baiyu.agent.kb;

import com.baiyu.agent.config.ChatModelFactory;
import com.baiyu.agent.kb.entity.*;
import com.baiyu.agent.kb.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class KbQaService {

    private static final Logger log = LoggerFactory.getLogger(KbQaService.class);
    private static final int SEARCH_TOP_K = 5;
    private static final double HIGH_THRESHOLD = 0.6;
    private static final double MEDIUM_THRESHOLD = 0.3;

    private final KnowledgeBaseService kbService;
    private final CitationRepository citationRepo;
    private final FeedbackRepository feedbackRepo;
    private final KbMessageRepository messageRepo;
    private final ChatClient chatClient;
    private final ChatModelFactory chatModelFactory;
    private final boolean allowClientModelKey;

    public KbQaService(
            KnowledgeBaseService kbService,
            CitationRepository citationRepo,
            FeedbackRepository feedbackRepo,
            KbMessageRepository messageRepo,
            ChatClient chatClient,
            ChatModelFactory chatModelFactory,
            @Value("${agent.security.allow-client-model-key:false}") boolean allowClientModelKey) {
        this.kbService = kbService;
        this.citationRepo = citationRepo;
        this.feedbackRepo = feedbackRepo;
        this.messageRepo = messageRepo;
        this.chatClient = chatClient;
        this.chatModelFactory = chatModelFactory;
        this.allowClientModelKey = allowClientModelKey;
    }

    @Transactional
    public QaResult ask(String spaceId, String question, String conversationId, String userId) {
        return ask(spaceId, question, conversationId, userId, null);
    }

    /**
     * Ask a question with optional per-request model API key.
     * The model key is only used when allow-client-model-key is enabled.
     * The key is never logged, persisted, or included in error messages.
     */
    @Transactional
    public QaResult ask(String spaceId, String question, String conversationId,
                        String userId, String modelApiKey) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (spaceId == null || spaceId.isBlank()) {
            throw new IllegalArgumentException("spaceId 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        // Determine which ChatClient to use
        ChatClient activeClient = chatClient;
        ChatModel perRequestModel = null;
        if (allowClientModelKey && modelApiKey != null && !modelApiKey.isBlank()) {
            perRequestModel = chatModelFactory.createChatModel(modelApiKey);
            activeClient = ChatClient.builder(perRequestModel).build();
        }

        // Save user message before retrieval
        String userMessageId = UUID.randomUUID().toString();
        KbMessage userMsg = new KbMessage(spaceId, conversationId, userMessageId, userId,
                "user", question, null, null);
        messageRepo.save(userMsg);

        // Read conversation history for multi-turn context (last 10 messages, excluding failed assistant responses)
        List<KbMessage> history = messageRepo.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<KbMessage> recentHistory = history.stream()
                .filter(m -> !"assistant".equals(m.getRole()) || (m.getContent() != null && !m.getContent().isBlank()
                        && !m.getContent().startsWith("回答生成失败")))
                .toList();
        // Keep only the most recent 20 records; history is ascending from the repository.
        if (recentHistory.size() > 20) {
            recentHistory = recentHistory.subList(recentHistory.size() - 20, recentHistory.size());
        }
        // Exclude the just-saved user message from history (it's already in the question)
        recentHistory = recentHistory.stream()
                .filter(m -> !userMessageId.equals(m.getMessageId()))
                .toList();

        List<Chunk> chunks = kbService.searchChunks(spaceId, question, SEARCH_TOP_K);

        if (chunks.isEmpty()) {
            String noContentAnswer = buildNoContentAnswer(question, recentHistory);
            String msgId = UUID.randomUUID().toString();
            KbMessage assistantMsg = new KbMessage(spaceId, conversationId, msgId, userId,
                    "assistant", noContentAnswer, "low", 0.0);
            messageRepo.save(assistantMsg);
            return new QaResult(msgId, noContentAnswer, Collections.emptyList(), "low", 0.0, conversationId);
        }

        String context = buildContext(chunks);
        String historyContext = buildHistoryContext(recentHistory);
        String augmentedPrompt = buildPrompt(context, historyContext, question);

        String answer;
        try {
            answer = activeClient.prompt()
                    .user(augmentedPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("LLM call failed for space '{}'", spaceId);
            answer = "回答生成失败，请稍后重试。";
        }

        if (answer == null || answer.isBlank()) {
            answer = "AI 返回了空回复，请重试。";
        }

        double topScore = scoreSimilarity(chunks.get(0).getContent(), question);
        String confidence = computeConfidence(topScore, chunks.size());

        String messageId = UUID.randomUUID().toString();
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            double score = scoreSimilarity(c.getContent(), question);
            Citation cit = new Citation(messageId, spaceId, c.getDocumentId(), c.getVersionId(), c.getId(), score);
            citations.add(citationRepo.save(cit));
        }

        // Save assistant message after generation
        KbMessage assistantMsg = new KbMessage(spaceId, conversationId, messageId, userId,
                "assistant", answer, confidence, topScore);
        messageRepo.save(assistantMsg);

        return new QaResult(messageId, answer, citations, confidence, topScore, conversationId);
    }

    public List<KbMessage> getMessages(String conversationId) {
        return messageRepo.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public Feedback submitFeedback(String messageId, String spaceId, String thumbs, String reason, String correction) {
        if (!"up".equals(thumbs) && !"down".equals(thumbs)) {
            throw new IllegalArgumentException("thumbs 必须为 'up' 或 'down'");
        }
        Feedback fb = new Feedback(messageId, spaceId, thumbs, reason, correction);
        return feedbackRepo.save(fb);
    }

    public List<Feedback> getFeedback(String spaceId) {
        return feedbackRepo.findBySpaceId(spaceId);
    }

    public List<Citation> getCitations(String messageId) {
        return citationRepo.findByMessageId(messageId);
    }

    private String buildContext(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            sb.append("[").append(i + 1).append("] ");
            if (c.getHeading() != null) sb.append(c.getHeading()).append(" > ");
            sb.append(c.getContent());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private String buildHistoryContext(List<KbMessage> history) {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前的对话历史，请参考上下文理解用户的追问：\n\n");
        for (KbMessage m : history) {
            String role = "user".equals(m.getRole()) ? "用户" : "助手";
            sb.append(role).append(": ").append(m.getContent());
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String buildPrompt(String context, String historyContext, String question) {
        if (historyContext.isEmpty()) {
            return """
                    基于以下知识库内容回答问题。如果内容中没有相关信息，请明确说明"知识库中未找到相关内容"。

                    知识库内容:
                    %s

                    问题: %s

                    请给出准确、简洁的回答，并在末尾标注引用的来源编号 [1], [2] 等。
                    """.formatted(context, question);
        }
        return """
                基于以下知识库内容和对话历史回答问题。如果知识库内容中没有相关信息，请明确说明"知识库中未找到相关内容"。
                结合对话历史理解用户的追问意图。

                %s
                知识库内容:
                %s

                问题: %s

                请给出准确、简洁的回答，并在末尾标注引用的来源编号 [1], [2] 等。
                """.formatted(historyContext, context, question);
    }

    private String buildNoContentAnswer(String question, List<KbMessage> history) {
        if (history.isEmpty()) {
            return "抱歉，当前知识空间中没有找到与您问题相关的内容。请尝试上传相关文档或调整问题措辞。";
        }
        return "知识库中未找到与当前问题直接相关的内容。根据对话上下文，请尝试更具体的提问，或上传相关文档以补充知识库。";
    }

    private String computeConfidence(double topScore, int chunkCount) {
        if (topScore >= HIGH_THRESHOLD && chunkCount >= 2) return "high";
        if (topScore >= MEDIUM_THRESHOLD) return "medium";
        return "low";
    }

    private double scoreSimilarity(String text, String query) {
        String textLower = text.toLowerCase();
        String queryLower = query.toLowerCase();
        Set<String> textTokens = tokenize(textLower);
        Set<String> queryTokens = tokenize(queryLower);
        if (queryTokens.isEmpty() || textTokens.isEmpty()) return 0;
        long matches = queryTokens.stream().filter(textTokens::contains).count();
        return (double) matches / Math.sqrt(textTokens.size() * queryTokens.size());
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String word : text.split("\\s+")) {
            if (word.isBlank()) continue;
            for (char c : word.toCharArray()) {
                if (isCjk(c)) {
                    tokens.add(String.valueOf(c));
                } else if (Character.isLetterOrDigit(c)) {
                    tokens.add(String.valueOf(Character.toLowerCase(c)));
                }
            }
        }
        return tokens;
    }

    private boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
    }

    public record QaResult(
            String messageId,
            String answer,
            List<Citation> citations,
            String confidence,
            double topScore,
            String conversationId
    ) {}
}
