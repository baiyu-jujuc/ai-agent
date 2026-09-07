package com.baiyu.agent.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {

    private CalculatorTool calculator;

    @BeforeEach
    void setUp() {
        calculator = new CalculatorTool();
    }

    @Test
    void addition() {
        String result = calculator.execute("2+3");
        assertTrue(result.contains("5"));
    }

    @Test
    void subtraction() {
        String result = calculator.execute("10-4");
        assertTrue(result.contains("6"));
    }

    @Test
    void multiplication() {
        String result = calculator.execute("6*7");
        assertTrue(result.contains("42"));
    }

    @Test
    void division() {
        String result = calculator.execute("20/4");
        assertTrue(result.contains("5"));
    }

    @Test
    void operatorPrecedence() {
        String result = calculator.execute("2+3*4");
        assertTrue(result.contains("14"));
    }

    @Test
    void parentheses() {
        String result = calculator.execute("(1+2)*3");
        assertTrue(result.contains("9"));
    }

    @Test
    void exponent() {
        String result = calculator.execute("2^10");
        assertTrue(result.contains("1024"));
    }

    @Test
    void negativeNumber() {
        String result = calculator.execute("-5+3");
        assertTrue(result.contains("-2"));
    }

    @Test
    void decimalCalculation() {
        String result = calculator.execute("3.14*2");
        assertTrue(result.contains("6.28"));
    }

    @Test
    void invalidExpression() {
        String result = calculator.execute("abc");
        assertTrue(result.contains("error") || result.contains("error"), result);
    }

    @Test
    void emptyInput() {
        String result = calculator.execute("");
        assertTrue(result.contains("error"), result);
    }

    @Test
    void complexExpression() {
        String result = calculator.execute("(2+3)*(4-1)^2");
        assertTrue(result.contains("45"), result);
    }
}
