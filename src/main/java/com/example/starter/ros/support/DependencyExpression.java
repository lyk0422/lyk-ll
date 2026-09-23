package com.example.starter.ros.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 依赖表达式求值器。
 *
 * <p>支持的语法（足以表达参数间依赖约束）：
 * <ul>
 *   <li>操作数：参数名（可含 {@code . _ -}）、数字、单引号字符串、{@code true}/{@code false}</li>
 *   <li>比较：{@code == != > >= < <=}</li>
 *   <li>逻辑：{@code && || !} 与括号</li>
 * </ul>
 *
 * <p>求值上下文是整笔更新后的候选快照，因此结果只取决于快照内容、与字段提交顺序无关。
 */
public final class DependencyExpression {

    private final String expression;
    private final List<Token> tokens;

    private DependencyExpression(String expression) {
        this.expression = expression;
        this.tokens = tokenize(expression);
    }

    /** 编译表达式；语法非法时抛出 IllegalArgumentException。 */
    public static DependencyExpression compile(String expression) {
        return new DependencyExpression(expression);
    }

    /**
     * 基于候选快照求值。
     *
     * @throws IllegalArgumentException 表达式引用了快照中不存在的参数
     */
    public boolean evaluate(Map<String, Object> candidate) {
        Parser parser = new Parser(tokens, candidate);
        boolean result = parser.parseOr();
        if (parser.hasNext()) {
            throw new IllegalArgumentException("unexpected token near '" + parser.peek().text + "'");
        }
        return result;
    }

    public String raw() {
        return expression;
    }

    // ---- 词法 ----

    private enum TokenType { VALUE, OP, LPAREN, RPAREN }

    private record Token(TokenType type, String text) {
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(TokenType.LPAREN, "("));
                i++;
            } else if (c == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")"));
                i++;
            } else if (c == '\'') {
                int end = input.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated string literal");
                }
                tokens.add(new Token(TokenType.VALUE, "S:" + input.substring(i + 1, end)));
                i = end + 1;
            } else if (i + 1 < input.length() && twoCharOp(input, i)) {
                tokens.add(new Token(TokenType.OP, input.substring(i, i + 2)));
                i += 2;
            } else if (c == '=' || c == '!' || c == '>' || c == '<' || c == '&' || c == '|') {
                tokens.add(new Token(TokenType.OP, String.valueOf(c)));
                i++;
            } else {
                int start = i;
                while (i < input.length() && !Character.isWhitespace(input.charAt(i))
                        && input.charAt(i) != '(' && input.charAt(i) != ')'
                        && input.charAt(i) != '\'' && !isOperatorChar(input.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("unexpected character '" + c + "'");
                }
                tokens.add(new Token(TokenType.VALUE, "V:" + input.substring(start, i)));
            }
        }
        return tokens;
    }

    private static boolean twoCharOp(String input, int i) {
        String pair = input.substring(i, i + 2);
        return pair.equals("==") || pair.equals("!=") || pair.equals(">=")
                || pair.equals("<=") || pair.equals("&&") || pair.equals("||");
    }

    private static boolean isOperatorChar(char c) {
        return c == '=' || c == '!' || c == '>' || c == '<' || c == '&' || c == '|';
    }

    // ---- 语法与求值 ----

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Object> candidate;
        private int pos;

        private Parser(List<Token> tokens, Map<String, Object> candidate) {
            this.tokens = tokens;
            this.candidate = candidate;
        }

        boolean hasNext() {
            return pos < tokens.size();
        }

        Token peek() {
            return tokens.get(pos);
        }

        private Token consume() {
            return tokens.get(pos++);
        }

        boolean parseOr() {
            boolean value = parseAnd();
            while (hasOperator("||")) {
                pos++;
                boolean right = parseAnd();
                value = value || right;
            }
            return value;
        }

        boolean parseAnd() {
            boolean value = parseUnary();
            while (hasOperator("&&")) {
                pos++;
                boolean right = parseUnary();
                value = value && right;
            }
            return value;
        }

        boolean parseUnary() {
            if (hasOperator("!")) {
                pos++;
                return !parseUnary();
            }
            return parseComparison();
        }

        boolean parseComparison() {
            Object left = parsePrimary();
            if (hasNext() && peek().type() == TokenType.OP
                    && !peek().text().equals("&&") && !peek().text().equals("||")) {
                String op = consume().text();
                Object right = parsePrimary();
                return compare(op, left, right);
            }
            if (left instanceof Boolean b) {
                return b;
            }
            throw new IllegalArgumentException("expression is not boolean");
        }

        Object parsePrimary() {
            if (!hasNext()) {
                throw new IllegalArgumentException("unexpected end of expression");
            }
            Token token = consume();
            if (token.type() == TokenType.LPAREN) {
                boolean inner = parseOr();
                if (!hasNext() || consume().type() != TokenType.RPAREN) {
                    throw new IllegalArgumentException("missing closing parenthesis");
                }
                return inner;
            }
            if (token.type() != TokenType.VALUE) {
                throw new IllegalArgumentException("unexpected token '" + token.text() + "'");
            }
            String payload = token.text().substring(2);
            if (token.text().startsWith("S:")) {
                return payload;
            }
            if ("true".equals(payload)) {
                return Boolean.TRUE;
            }
            if ("false".equals(payload)) {
                return Boolean.FALSE;
            }
            try {
                return Long.parseLong(payload);
            } catch (NumberFormatException ignored) {
                // continue
            }
            try {
                return Double.parseDouble(payload);
            } catch (NumberFormatException ignored) {
                // continue
            }
            if (!candidate.containsKey(payload)) {
                throw new IllegalArgumentException("dependency references absent parameter '" + payload + "'");
            }
            return candidate.get(payload);
        }

        private boolean hasOperator(String op) {
            return hasNext() && peek().type() == TokenType.OP && peek().text().equals(op);
        }

        @SuppressWarnings("unchecked")
        private static boolean compare(String op, Object left, Object right) {
            if (left == null || right == null) {
                return "==".equals(op) && left == null && right == null;
            }
            if ("==".equals(op)) {
                return valueEquals(left, right);
            }
            if ("!=".equals(op)) {
                return !valueEquals(left, right);
            }
            if (left instanceof Number ln && right instanceof Number rn) {
                int cmp = Double.compare(ln.doubleValue(), rn.doubleValue());
                return switch (op) {
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    default -> throw new IllegalArgumentException("unknown operator '" + op + "'");
                };
            }
            if (left.getClass() == right.getClass() && left instanceof Comparable) {
                int cmp = ((Comparable<Object>) left).compareTo(right);
                return switch (op) {
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    default -> throw new IllegalArgumentException("unknown operator '" + op + "'");
                };
            }
            throw new IllegalArgumentException("cannot compare " + left + " and " + right);
        }

        private static boolean valueEquals(Object left, Object right) {
            if (left instanceof Number ln && right instanceof Number rn) {
                return Double.compare(ln.doubleValue(), rn.doubleValue()) == 0;
            }
            return left.equals(right);
        }
    }
}
