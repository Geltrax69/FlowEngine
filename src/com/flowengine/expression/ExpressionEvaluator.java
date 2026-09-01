package com.flowengine.expression;

import java.util.*;

/**
 * Lightweight expression evaluator for workflow conditions.
 * Supports: variable lookups, numeric + string comparisons, boolean logic.
 *
 * Grammar (simplified):
 *   expr     := or_expr
 *   or_expr  := and_expr ('OR' and_expr)*
 *   and_expr := not_expr ('AND' not_expr)*
 *   not_expr := 'NOT' not_expr | atom
 *   atom     := '(' expr ')' | comparison
 *   comparison := value comp_op value
 *   comp_op  := '==' | '!=' | '>=' | '<=' | '>' | '<'
 *   value    := number | string | identifier
 *
 * Variables are looked up in a passed context map.
 */
public class ExpressionEvaluator {

    private final String expression;
    private int pos;

    public ExpressionEvaluator(String expression) {
        this.expression = expression == null ? "" : expression.trim();
        this.pos = 0;
    }

    public boolean evaluate(Map<String, Object> context) {
        if (expression.isEmpty()) return true;
        try {
            Boolean result = parseOr(context);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to evaluate expression: '" + expression + "' — " + e.getMessage(), e);
        }
    }

    private boolean isAtEnd() { return pos >= expression.length(); }

    private Boolean parseOr(Map<String, Object> ctx) {
        Boolean left = parseAnd(ctx);
        while (true) {
            skipWhitespace();
            if (isAtEnd()) break;
            if (matchKeyword("OR")) {
                Boolean right = parseAnd(ctx);
                left = (left || right);
            } else break;
        }
        return left;
    }

    private Boolean parseAnd(Map<String, Object> ctx) {
        Boolean left = parseNot(ctx);
        while (true) {
            skipWhitespace();
            if (isAtEnd()) break;
            if (peek() == 'A' && peekAt(1) == 'N' && peekAt(2) == 'D') {
                pos += 3;
                Boolean right = parseNot(ctx);
                left = (left && right);
            } else break;
        }
        return left;
    }

    private Boolean parseNot(Map<String, Object> ctx) {
        skipWhitespace();
        if (isAtEnd()) return false; // bare value at end is fine
        if (matchKeyword("NOT")) {
            return !parseNot(ctx);
        }
        return parseAtom(ctx);
    }

    private Boolean parseAtom(Map<String, Object> ctx) {
        skipWhitespace();
        if (isAtEnd()) throw new RuntimeException("Unexpected end of expression");
        if (peek() == '(') {
            consume('(');
            Boolean b = parseOr(ctx);
            skipWhitespace();
            consume(')');
            return b;
        }
        return parseComparison(ctx);
    }

    private Boolean parseComparison(Map<String, Object> ctx) {
        Object left = parseValue(ctx);
        skipWhitespace();
        if (isAtEnd() || peek() == ')' || peek() == ',' || isLogicalOp(peek())) {
            // No operator — single value is truthy
            return isTruthy(left);
        }
        String op = parseOperator();
        skipWhitespace();
        if (isAtEnd()) throw new RuntimeException("Expected right operand");
        Object right = parseValue(ctx);
        return compare(left, op, right);
    }

    private boolean isLogicalOp(char c) {
        return c == 'A' || c == 'O' || c == 'N';
    }

    private boolean isTruthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof String s) return !s.isEmpty();
        return true;
    }

    private Object parseValue(Map<String, Object> ctx) {
        skipWhitespace();
        if (isAtEnd()) throw new RuntimeException("Unexpected end of expression");
        if (peek() == '"' || peek() == '\'') {
            return parseString(peek() == '"' ? '"' : '\'');
        }
        if (Character.isDigit(peek()) || peek() == '-' || peek() == '.') {
            return parseNumber();
        }
        if (peek() == 't' && matchKeyword("true")) return Boolean.TRUE;
        if (peek() == 'f' && matchKeyword("false")) return Boolean.FALSE;
        if (peek() == 'n' && matchKeyword("null")) return null;
        return parseIdentifier(ctx);
    }

    private String parseOperator() {
        skipWhitespace();
        char c = peek();
        if (c == '=' && peekAt(1) == '=') { pos += 2; return "=="; }
        if (c == '!' && peekAt(1) == '=') { pos += 2; return "!="; }
        if (c == '>' && peekAt(1) == '=') { pos += 2; return ">="; }
        if (c == '<' && peekAt(1) == '=') { pos += 2; return "<="; }
        if (c == '>') { pos++; return ">"; }
        if (c == '<') { pos++; return "<"; }
        throw new RuntimeException("Expected operator at position " + pos);
    }

    private String parseString(char quote) {
        consume(quote);
        StringBuilder sb = new StringBuilder();
        while (pos < expression.length() && expression.charAt(pos) != quote) {
            sb.append(expression.charAt(pos++));
        }
        consume(quote);
        return sb.toString();
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < expression.length() && (Character.isDigit(expression.charAt(pos)) || expression.charAt(pos) == '.')) {
            pos++;
        }
        String num = expression.substring(start, pos);
        if (num.contains(".")) return Double.parseDouble(num);
        return Long.parseLong(num);
    }

    private Object parseIdentifier(Map<String, Object> ctx) {
        int start = pos;
        while (pos < expression.length() && (Character.isLetterOrDigit(expression.charAt(pos)) || expression.charAt(pos) == '_')) {
            pos++;
        }
        String name = expression.substring(start, pos);
        if (name.isEmpty()) throw new RuntimeException("Expected identifier at " + start);
        // If the identifier is ALL_CAPS, treat it as a string literal (e.g., PREMIUM, SUCCESS)
        if (name.equals(name.toUpperCase()) && !name.matches("^[a-z].*")) {
            return name;
        }
        // Otherwise look up in context
        Object val = ctx.get(name);
        // If not found in context, treat as a string literal
        return val != null ? val : name;
    }

    private boolean matchKeyword(String kw) {
        skipWhitespace();
        if (pos + kw.length() > expression.length()) return false;
        for (int i = 0; i < kw.length(); i++) {
            if (expression.charAt(pos + i) != kw.charAt(i)) return false;
        }
        // make sure not part of a larger word
        if (pos + kw.length() < expression.length()) {
            char next = expression.charAt(pos + kw.length());
            if (Character.isLetterOrDigit(next) || next == '_') return false;
        }
        pos += kw.length();
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Boolean compare(Object left, String op, Object right) {
        if (left == null || right == null) {
            return switch (op) {
                case "==" -> left == right;
                case "!=" -> left != right;
                default -> false;
            };
        }
        if (left instanceof Number || right instanceof Number) {
            double l = toDouble(left);
            double r = toDouble(right);
            return switch (op) {
                case "==" -> l == r;
                case "!=" -> l != r;
                case ">" -> l > r;
                case "<" -> l < r;
                case ">=" -> l >= r;
                case "<=" -> l <= r;
                default -> false;
            };
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            boolean l = toBool(left);
            boolean r = toBool(right);
            return switch (op) {
                case "==" -> l == r;
                case "!=" -> l != r;
                default -> false;
            };
        }
        int cmp = String.valueOf(left).compareTo(String.valueOf(right));
        return switch (op) {
            case "==" -> cmp == 0;
            case "!=" -> cmp != 0;
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private boolean toBool(Object o) {
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private char peek() { return pos < expression.length() ? expression.charAt(pos) : '\0'; }
    private char peekAt(int offset) {
        return pos + offset < expression.length() ? expression.charAt(pos + offset) : '\0';
    }
    private void consume(char c) {
        skipWhitespace();
        if (peek() != c) throw new RuntimeException("Expected '" + c + "' at " + pos);
        pos++;
    }
    private void skipWhitespace() {
        while (pos < expression.length() && Character.isWhitespace(expression.charAt(pos))) pos++;
    }

    public static boolean evaluate(String expr, Map<String, Object> ctx) {
        return new ExpressionEvaluator(expr).evaluate(ctx);
    }
}
