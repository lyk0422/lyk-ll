package com.example.starter.idempotency;

import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsReviseRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.CustomsWithdrawRequest;
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
    public static final String CUSTOMS_WITHDRAW = "CUSTOMS_WITHDRAW";
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

    // ============================ 进口通关（结构化无歧义指纹） ============================

    /** 通关登记指纹：requestId + 单号 + 企业/品种 + 精确截止时刻 + 总支数。 */
    public static String customsRegister(CustomsRegisterRequest request) {
        return sha256(structured(
                CUSTOMS_REGISTER,
                request.requestId(),
                request.declarationNo(),
                request.enterpriseName(),
                request.productName(),
                request.validUntil().toString(),
                request.totalDoses().toString()));
    }

    /** 子批绑定指纹：requestId + 单号 + 期望版本 + 批号 + 申报支数。 */
    public static String customsBind(String declarationNo, CustomsBindRequest request) {
        return sha256(structured(
                CUSTOMS_BIND,
                request.requestId(),
                declarationNo,
                request.expectedVersion().toString(),
                request.batchNo(),
                request.declaredDoses().toString()));
    }

    /** 撤回子批指纹：requestId + 单号 + 批号 + 期望版本。 */
    public static String customsWithdraw(String declarationNo, String batchNo, CustomsWithdrawRequest request) {
        return sha256(structured(
                CUSTOMS_WITHDRAW,
                request.requestId(),
                declarationNo,
                batchNo,
                request.expectedVersion().toString()));
    }

    /** 通关撤销指纹：requestId + 单号 + 期望版本。 */
    public static String customsRevoke(String declarationNo, CustomsRevokeRequest request) {
        return sha256(structured(
                CUSTOMS_REVOKE,
                request.requestId(),
                declarationNo,
                request.expectedVersion().toString()));
    }

    /** 通关修订指纹：requestId + 单号 + 期望版本 + 新总支数。 */
    public static String customsRevise(String declarationNo, CustomsReviseRequest request) {
        return sha256(structured(
                CUSTOMS_REVISE,
                request.requestId(),
                declarationNo,
                request.expectedVersion().toString(),
                request.newTotalDoses().toString()));
    }

    /**
     * 结构化无歧义编码：每个字段以 “长度:内容” 前缀编码后拼接，
     * 合法文本即使包含分隔符也不会与别的字段组合产生相同编码。
     */
    static String structured(String... fields) {
        StringBuilder builder = new StringBuilder();
        for (String field : fields) {
            String value = field == null ? "" : field.trim();
            builder.append(value.length()).append(':').append(value).append(';');
        }
        return builder.toString();
    }

    private static String inspection(InspectionInput input) {
        return input.itemCode().trim() + "=" + input.measuredValue().trim();
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
