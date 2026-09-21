package com.baiyu.agent.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 评测集里的一条用例。
 *
 * <p>字段名用 snake_case（{@code space_id} / {@code doc_keys} …）：JSONL 是<b>给人看和给人改</b>的，
 * 蛇形命名和文档、数据库列名保持一致，比驼峰更不容易写错。
 * 这里的 {@code @JsonProperty} 就是为这个选择付出的必要代价——不写的话，
 * Jackson 会把 {@code spaceId} 解析成 null，跑批时才炸。
 */
public record EvalCase(
        String id,
        String question,
        @JsonProperty("space_id")
        String spaceId,
        @JsonProperty("doc_keys")
        List<String> docKeys,
        @JsonProperty("expected_doc_ids")
        List<String> expectedDocIds,
        @JsonProperty("reference_answer")
        String referenceAnswer,
        List<String> tags
) {

    /**
     * 是否是"文档里没有答案、应当拒答"的用例。
     *
     * <p>这类用例必须和普通用例分开统计：它们没有期望文档，
     * 如果混进 Hit@5 的分母，会把指标莫名其妙地拉低（检索总会返回 top-5，永远不可能"命中零个文档"）。
     */
    public boolean expectsRefusal() {
        return tags != null && tags.contains("no-answer");
    }
}
