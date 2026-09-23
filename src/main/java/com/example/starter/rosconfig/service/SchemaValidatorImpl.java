package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.model.ParameterSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 默认 Schema 校验器。
 *
 * <p>校验顺序：</p>
 * <ol>
 *   <li>候选快照中每个已定义参数都必须匹配 Schema 类型（未定义参数名拒绝）；</li>
 *   <li>必填参数必须有非 null 值；</li>
 *   <li>依赖表达式基于整笔更新合并后的完整候选快照求值，必须返回布尔真。</li>
 * </ol>
 *
 * <p>依赖表达式支持：参数名引用（如 {@code max_speed}）、数字/字符串/布尔字面量、
 * {@code == != > >= < <=}、{@code and or not}（及 {@code && || !}）与括号。
 * 表达式在完整快照上一次性求值，不因变更字段的先后顺序而产生不同结果。</p>
 */
@Component
public class SchemaValidatorImpl implements SchemaValidator {

    @Override
    public String validate(List<ParameterSchema> schemas, Map<String, Object> candidate) {
        Map<String, ParameterSchema> byName = new java.util.HashMap<>();
        for (ParameterSchema schema : schemas) {
            byName.put(schema.name(), schema);
        }

        for (Map.Entry<String, Object> entry : candidate.entrySet()) {
            ParameterSchema schema = byName.get(entry.getKey());
            if (schema == null) {
                return "unknown parameter: " + entry.getKey();
            }
            if (entry.getValue() != null && !schema.type().matches(entry.getValue())) {
                return "parameter " + entry.getKey() + " expects " + schema.type()
                        + " but got " + entry.getValue().getClass().getSimpleName();
            }
        }

        for (ParameterSchema schema : schemas) {
            if (schema.required() && candidate.get(schema.name()) == null) {
                return "missing required parameter: " + schema.name();
            }
        }

        for (ParameterSchema schema : schemas) {
            String expr = schema.dependsOn();
            if (expr == null || expr.isBlank()) {
                continue;
            }
            Object result;
            try {
                result = new ExpressionParser(expr, candidate).parse();
            } catch (ExpressionException e) {
                return "dependency expression error for " + schema.name() + ": " + e.getMessage();
            }
            if (!(result instanceof Boolean b)) {
                return "dependency expression for " + schema.name() + " is not boolean";
            }
            if (!b) {
                return "dependency violated for parameter: " + schema.name();
            }
        }
        return null;
    }

    static class ExpressionException extends RuntimeException {
        ExpressionException(String message) {
            super(message);
        }
    }

    /** 极简递归下降表达式解析器：expr := orExpr；文法：or and equality comparison unary primary。 */
    static final class ExpressionParser {
        private final String src;
        private final Map<String, Object> values;
        private int pos;

        ExpressionParser(String src, Map<String, Object> values) {
            this.src = src;
            this.values = values;
        }

        Object parse() {
            Object v = parseOr();
            skipSpaces();
            if (pos < src.length()) {
                throw new ExpressionException("unexpected input at " + pos);
            }
            return v;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (true) {
                skipSpaces();
                if (matchWord("or") || matchSymbol("||")) {
                    Object right = parseAnd();
                    left = toBool(left) || toBool(right);
                } else {
                    return left;
                }
            }
        }

        private Object parseAnd() {
            Object left = parseNot();
            while (true) {
                skipSpaces();
                if (matchWord("and") || matchSymbol("&&")) {
                    Object right = parseNot();
                    left = toBool(left) && toBool(right);
                } else {
                    return left;
                }
            }
        }

        private Object parseNot() {
            skipSpaces();
            if (matchWord("not") || matchSymbol("!")) {
                Object v = parseNot();
                return !toBool(v);
            }
            return parseComparison();
        }

        private Object parseComparison() {
            Object left = parsePrimary();
            skipSpaces();
            String op = null;
            if (matchSymbol(">=")) {
                op = ">=";
            } else if (matchSymbol("<=")) {
                op = "<=";
            } else if (matchSymbol("==")) {
                op = "==";
            } else if (matchSymbol("!=")) {
                op = "!=";
            } else if (matchSymbol(">")) {
                op = ">";
            } else if (matchSymbol("<")) {
                op = "<";
            }
            if (op == null) {
                return left;
            }
            Object right = parsePrimary();
            return compare(op, left, right);
        }

        private Object parsePrimary() {
            skipSpaces();
            if (pos >= src.length()) {
                throw new ExpressionException("unexpected end of expression");
            }
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                Object v = parseOr();
                skipSpaces();
                if (pos >= src.length() || src.charAt(pos) != ')') {
                    throw new ExpressionException("missing ')'");
                }
                pos++;
                return v;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                pos++;
                int start = pos;
                while (pos < src.length() && src.charAt(pos) != quote) {
                    pos++;
                }
                if (pos >= src.length()) {
                    throw new ExpressionException("unterminated string literal");
                }
                String s = src.substring(start, pos);
                pos++;
                return s;
            }
            if (Character.isDigit(c) || c == '-' || c == '.') {
                return parseNumber();
            }
            if (Character.isLetter(c) || c == '_') {
                return parseWordOrRef();
            }
            throw new ExpressionException("unexpected character '" + c + "'");
        }

        private Object parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            boolean dot = false;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                if (src.charAt(pos) == '.') {
                    if (dot) {
                        throw new ExpressionException("invalid number");
                    }
                    dot = true;
                }
                pos++;
            }
            String token = src.substring(start, pos);
            try {
                return dot ? Double.parseDouble(token) : Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw new ExpressionException("invalid number: " + token);
            }
        }

        private Object parseWordOrRef() {
            int start = pos;
            while (pos < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            String word = src.substring(start, pos);
            if (word.equals("true")) {
                return Boolean.TRUE;
            }
            if (word.equals("false")) {
                return Boolean.FALSE;
            }
            if (word.equals("null")) {
                return null;
            }
            if (!values.containsKey(word)) {
                throw new ExpressionException("unknown parameter reference: " + word);
            }
            return values.get(word);
        }

        private boolean matchWord(String word) {
            if (src.regionMatches(true, pos, word, 0, word.length())
                    && (pos + word.length() >= src.length()
                        || !isIdentChar(src.charAt(pos + word.length())))) {
                pos += word.length();
                return true;
            }
            return false;
        }

        private boolean matchSymbol(String symbol) {
            if (src.regionMatches(pos, symbol, 0, symbol.length())) {
                pos += symbol.length();
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private static boolean isIdentChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        private boolean toBool(Object v) {
            if (v instanceof Boolean b) {
                return b;
            }
            throw new ExpressionException("boolean value required but got: " + v);
        }

        @SuppressWarnings("unchecked")
        private Object compare(String op, Object left, Object right) {
            if (op.equals("==")) {
                return java.util.Objects.equals(normalize(left), normalize(right));
            }
            if (op.equals("!=")) {
                return !java.util.Objects.equals(normalize(left), normalize(right));
            }
            if (!(left instanceof Number ln) || !(right instanceof Number rn)) {
                throw new ExpressionException("ordering comparison requires numbers: " + left + ", " + right);
            }
            double l = ln.doubleValue();
            double r = rn.doubleValue();
            return switch (op) {
                case ">" -> l > r;
                case "<" -> l < r;
                case ">=" -> l >= r;
                case "<=" -> l <= r;
                default -> throw new ExpressionException("unknown operator: " + op);
            };
        }

        /** 整数/浮点混合比较时统一数值口径；null 保持 null。 */
        private Object normalize(Object v) {
            if (v instanceof Long l) {
                return l.doubleValue();
            }
            return v;
        }
    }
}
