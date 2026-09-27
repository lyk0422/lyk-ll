package com.example.starter.idempotency;

import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.CustomsAmendRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
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
 * 幂等指纹：覆盖 requestId 与全部业务字段（含版本、集合、精确时刻）的 SHA-256。
 *
 * <p>指纹采用结构化长度前缀编码：每个字段编码为 {@code V<字节长度>:<原文>}，空值编码为 {@code N}，
 * 集合编码为元素个数后逐个长度前缀。字段边界与分隔符无歧义——合法文本即使含有
 * "|"、","、":" 等字符，不同字段拆分也必然产生不同编码。
 */
public final class Fingerprints {

    public static final String DECLARE = "DECLARE";
    public static final String CORRECT = "CORRECT";
    public static final String ISSUE = "ISSUE";
    public static final String CUSTOMS_REGISTER = "CUSTOMS_REGISTER";
    public static final String CUSTOMS_BIND = "CUSTOMS_BIND";
    public static final String CUSTOMS_REVOKE = "CUSTOMS_REVOKE";
    public static final String CUSTOMS_AMEND = "CUSTOMS_AMEND";
    public static final String CUSTOMS_WITHDRAW = "CUSTOMS_WITHDRAW";

    private Fingerprints() {
    }

    /** 申报指纹：requestId + 企业/品种/批号 + 资料目录 + 全部检验实测值。 */
    public static String declare(DeclareRequest request) {
        List<String> documents = sorted(request.documents());
        List<String> inspections = request.inspections().stream()
                .map(Fingerprints::inspection)
                .sorted()
                .toList();
        return sha256(structured(
                field(DECLARE),
                field(request.requestId()),
                field(request.enterpriseName().trim()),
                field(request.productName().trim()),
                field(request.batchNo().trim()),
                collection(documents),
                collection(inspections)));
    }

    /** 补正指纹：requestId + 批号 + 补正后完整资料目录。 */
    public static String correct(String batchNo, CorrectRequest request) {
        return sha256(structured(
                field(CORRECT),
                field(request.requestId()),
                field(batchNo.trim()),
                collection(sorted(request.documents()))));
    }

    /** 签发指纹：requestId + 批号。 */
    public static String issue(String batchNo, IssueRequest request) {
        return sha256(structured(
                field(ISSUE),
                field(request.requestId()),
                field(batchNo.trim())));
    }

    /** 通关登记指纹：requestId + 单号 + 企业/品种 + 有效截止 + 可核销总数。 */
    public static String customsRegister(CustomsRegisterRequest request) {
        return sha256(structured(
                field(CUSTOMS_REGISTER),
                field(request.requestId()),
                field(request.declarationNo().trim()),
                field(request.enterpriseName().trim()),
                field(request.productName().trim()),
                field(request.validUntil().toString()),
                field(request.totalQuantity().toString())));
    }

    /** 通关绑定指纹：requestId + 单号 + 子批号 + 申报支数 + 期望版本 + 精确时刻。 */
    public static String customsBind(CustomsBindRequest request) {
        return sha256(structured(
                field(CUSTOMS_BIND),
                field(request.requestId()),
                field(request.declarationNo().trim()),
                field(request.batchNo().trim()),
                field(request.declaredQuantity().toString()),
                field(request.expectedVersion().toString()),
                field(request.occurredAt().toString())));
    }

    /** 通关撤销指纹：requestId + 单号 + 期望版本 + 精确时刻。 */
    public static String customsRevoke(CustomsRevokeRequest request) {
        return sha256(structured(
                field(CUSTOMS_REVOKE),
                field(request.requestId()),
                field(request.declarationNo().trim()),
                field(request.expectedVersion().toString()),
                field(request.occurredAt().toString())));
    }

    /** 通关修订指纹：requestId + 单号 + 修订后总数 + 期望版本 + 精确时刻。 */
    public static String customsAmend(CustomsAmendRequest request) {
        return sha256(structured(
                field(CUSTOMS_AMEND),
                field(request.requestId()),
                field(request.declarationNo().trim()),
                field(request.newTotalQuantity().toString()),
                field(request.expectedVersion().toString()),
                field(request.occurredAt().toString())));
    }

    /** 签发前撤回指纹：requestId + 子批号 + 期望版本 + 精确时刻。 */
    public static String customsWithdraw(CustomsWithdrawRequest request) {
        return sha256(structured(
                field(CUSTOMS_WITHDRAW),
                field(request.requestId()),
                field(request.batchNo().trim()),
                field(request.expectedVersion().toString()),
                field(request.occurredAt().toString())));
    }

    // ============================ 结构化编码 ============================

    /**
     * 单字段长度前缀编码：{@code V<UTF-8 字节长度>:<原文>}；空值编码为 {@code N}。
     * 长度前缀使字段边界无歧义，原文中的任意分隔符不参与结构解析。
     */
    static String field(String value) {
        if (value == null) {
            return "N";
        }
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        return "V" + byteLength + ":" + value;
    }

    /** 集合编码：元素个数后逐个长度前缀，空集合与含一个空串的集合可区分。 */
    static String collection(List<String> values) {
        StringBuilder builder = new StringBuilder("C").append(values.size());
        for (String value : values) {
            builder.append('[').append(field(value)).append(']');
        }
        return builder.toString();
    }

    /** 拼接各字段编码形成完整规范化文本。 */
    static String structured(String... encodedFields) {
        return String.join("", encodedFields);
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
