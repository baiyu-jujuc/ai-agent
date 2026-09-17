package com.baiyu.agent.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 成本计算的单元测试。
 * 对应验收指标"成本计算正确"：抽记录按「单价快照 × tokens」复算要对得上。
 */
class PricingCalculatorTest {

    private GatewayProperties properties;
    private PricingCalculator calculator;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        GatewayProperties.Price price = new GatewayProperties.Price();
        // 1 微元 / token（prompt），2 微元 / token（completion）——便于心算复算
        price.setPromptPerMillion(1_000_000L);
        price.setCompletionPerMillion(2_000_000L);
        properties.getPricing().getModels().put("m1", price);
        calculator = new PricingCalculator(properties);
    }

    @Test
    void costIsIntegerMicrosFromUnitPriceSnapshot() {
        // 1000 * 1 + 500 * 2 = 2000 微元
        assertEquals(2000L, calculator.costMicros("m1", 1000, 500));
    }

    @Test
    void zeroTokensCostZero() {
        assertEquals(0L, calculator.costMicros("m1", 0, 0));
    }

    @Test
    void unconfiguredModelCostsZeroWithoutThrowing() {
        assertEquals(0L, calculator.costMicros("unknown-model", 9999, 9999));
        assertEquals(0L, calculator.priceOf("unknown-model").promptPerMillion());
    }

    @Test
    void priceOfReturnsConfiguredSnapshot() {
        PricingCalculator.UnitPrice price = calculator.priceOf("m1");
        assertEquals(1_000_000L, price.promptPerMillion());
        assertEquals(2_000_000L, price.completionPerMillion());
    }

    @Test
    void largeTokenCountsDoNotOverflowIntArithmetic() {
        // 中间结果 2_000_000_000 tokens × 1_000_000 微元/百万 = 2e15，超过 int 范围（约 2.1e9），
        // 所以必须先转 long 再乘，否则会溢出成负数；正确结果是 2e9 微元。
        assertEquals(2_000_000_000L, calculator.costMicros("m1", 2_000_000_000, 0));
    }

    @Test
    void integerDivisionTruncatesLikeTheVerificationScript() {
        // 每 token 不足 1 微元时按整数截断（与验收脚本的 Floor 口径一致）
        GatewayProperties properties2 = new GatewayProperties();
        GatewayProperties.Price cheap = new GatewayProperties.Price();
        cheap.setPromptPerMillion(1L);          // 1 微元 / 百万 token
        cheap.setCompletionPerMillion(1L);
        properties2.getPricing().getModels().put("cheap", cheap);
        PricingCalculator cheapCalculator = new PricingCalculator(properties2);

        assertEquals(0L, cheapCalculator.costMicros("cheap", 999_999, 0));
        assertEquals(1L, cheapCalculator.costMicros("cheap", 1_000_000, 0));
    }
}
