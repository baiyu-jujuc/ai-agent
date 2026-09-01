package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTool implements ToolComponent {

    @Tool(name = "calculator", description = "Evaluates mathematical expressions like '2+3*4', '(1+2)*3', '2^10'")
    public String calculate(String input) {
        String expression = input.trim();
        try {
            double result = eval(expression);
            return String.format("Result: %s = %s", expression, result);
        } catch (Exception e) {
            return "Calculation error: " + e.getMessage();
        }
    }

    private double eval(String expr) {
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int c) { while (ch == ' ') nextChar(); if (ch == c) { nextChar(); return true; } return false; }
            double parse() { nextChar(); double x = parseExpr(); if (pos < expr.length()) throw new RuntimeException("Unexpected: " + (char) ch); return x; }
            double parseExpr() { double x = parseTerm(); for (;;) { if (eat('+')) x += parseTerm(); else if (eat('-')) x -= parseTerm(); else return x; } }
            double parseTerm() { double x = parseFactor(); for (;;) { if (eat('*')) x *= parseFactor(); else if (eat('/')) x /= parseFactor(); else return x; } }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x; int startPos = pos;
                if (eat('(')) { x = parseExpr(); eat(')'); }
                else if ((ch >= '0' && ch <= '9') || ch == '.') { while ((ch >= '0' && ch <= '9') || ch == '.') nextChar(); x = Double.parseDouble(expr.substring(startPos, pos)); }
                else throw new RuntimeException("Unexpected: " + (char) ch);
                if (eat('^')) x = Math.pow(x, parseFactor());
                return x;
            }
        }.parse();
    }
}
