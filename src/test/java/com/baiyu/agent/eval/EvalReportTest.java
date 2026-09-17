package com.baiyu.agent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvalReportTest {

    @Test
    void p95ReturnsZeroForEmptyInput() {
        assertEquals(0L, EvalReport.p95(List.of()));
    }

    @Test
    void p95PicksCeilIndexLikeTheVerificationScript() {
        List<Long> latencies = List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 10000L);
        // ceil(0.95 × 10) = 10 → 第 10 个（下标 9）
        assertEquals(10000L, EvalReport.p95(latencies));

        // ceil(0.95 × 4) = 4 → 第 4 个（下标 3）
        assertEquals(400L, EvalReport.p95(List.of(100L, 200L, 300L, 400L)));
    }

    @Test
    void judgeScoreParsingAcceptsOnlyZeroToThree() {
        assertEquals(3, EvalRunner.parseScore("3"));
        assertEquals(2, EvalRunner.parseScore("评分：2 分"));
        assertEquals(0, EvalRunner.parseScore("0分"));
        assertNull(EvalRunner.parseScore("无法评分"));
        assertNull(EvalRunner.parseScore(null));
    }
}
