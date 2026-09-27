package com.example.starter.idempotency;

import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.InspectionInput;
import com.example.starter.dto.IssueRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段的 SHA-256。
 * 集合类字段按代码排序归一化，保证同参指纹一致、异参指纹不同。
 */
public final class Fingerprints {

    public static final String DECLARE = "DECLARE";
    public static final String CORRECT = "CORRECT";
    public static final String ISSUE = "ISSUE";
    public static final String CUSTOMS_REGISTER = "CUSTOMS_REGISTER";
    public static final String CUSTOMS_BIND = "CUSTOMS_BIND";
    public static final String CUSTOMS_REVOKE = "CUSTOMS_REVOKE";
    public static final String CUSTOMS_REVISE = "CUSTOMS_REVISE";

    private Fingerprints() {
    }

    /** 申报指纹：requestId + 企业/品种/批号 + 资料目录 + 全部检验实测值。 */
    public static String declare(DeclareRequest request) {
        List<String> documents = sorted(request.documents());
        List<String> inspections = request.inspections().stream()
                .map(Fingerprints::inspection)
                .sorted()
                .toList();
        String canonical = String.join("|",
                DECLARE,
                request.requestId(),
                request.enterpriseName().trim(),
                request.productName().trim(),
                request.batchNo().trim(),
                String.join(",", documents),
                String.join(",", inspections));
        return sha256(canonical);
    }

    /** 补正指纹：requestId + 批号 + 补正后完整资料目录。 */
    public static String correct(String batchNo, CorrectRequest request) {
        String canonical = String.join("|",
                CORRECT,
                request.requestId(),
                batchNo.trim(),
                String.join(",", sorted(request.documents())));
        return sha256(canonical);
    }

    /** 签发指纹：requestId + 批号。 */
    public static String issue(String batchNo, IssueRequest request) {
        String canonical = String.join("|",
                ISSUE,
                request.requestId(),
                batchNo.trim());
        return sha256(canonical);
    }

    private static String inspection(InspectionInput input) {
        return input.itemCode().trim() + "=" + input.measuredValue().trim();
    }

    /**
     * 结构化无歧义指纹：字段按长度前缀编码（类型标记 + 长度 + 原文），
     * 合法文本即使包含分隔符（如 "|"、","、":"）也不会与字段边界混淆。
     * 集合按元素顺序逐个编码并带元素计数，null 与空串严格区分。
     *
     * @param operation 操作类型
     * @param fields    完整业务参数（String/Number/Boolean/Instant/List，null 允许）
     */
    public static String structured(String operation, Object... fields) {
        StringBuilder canonical = new StringBuilder();
        encode(canonical, operation);
        for (Object field : fields) {
            encode(canonical, field);
        }
        return sha256(canonical.toString());
    }

    private static void encode(StringBuilder out, Object value) {
        if (value == null) {
            out.append("N;");
        } else if (value instanceof String text) {
            out.append('S').append(text.length()).append(':').append(text).append(';');
        } else if (value instanceof Number || value instanceof Boolean) {
            String literal = value.toString();
            out.append('L').append(literal.length()).append(':').append(literal).append(';');
        } else if (value instanceof java.time.temporal.TemporalAccessor) {
            String literal = value.toString();
            out.append('T').append(literal.length()).append(':').append(literal).append(';');
        } else if (value instanceof List<?> list) {
            out.append('A').append(list.size()).append(':');
            for (Object element : list) {
                encode(out, element);
            }
            out.append(';');
        } else {
            throw new IllegalArgumentException("不支持的指纹字段类型: " + value.getClass());
        }
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().map(String::trim).sorted().toList();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
