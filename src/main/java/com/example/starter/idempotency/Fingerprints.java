package com.example.starter.idempotency;

import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.CustomsAmendRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.InspectionInput;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.SubbatchWithdrawRequest;

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
    public static final String CUSTOMS_AMEND = "CUSTOMS_AMEND";
    public static final String SUBBATCH_WITHDRAW = "SUBBATCH_WITHDRAW";
    public static final String COLDCHAIN_SUBMIT = "COLDCHAIN_SUBMIT";

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

    // ==================== 进口通关流程：结构化长度前缀指纹 ====================

    /** 通关单登记指纹：操作 + 时刻 + 版本无关的全部登记字段（含集合时按计数+长度前缀归一化）。 */
    public static String customsRegister(CustomsRegisterRequest request) {
        CanonicalBuilder cb = new CanonicalBuilder(CUSTOMS_REGISTER);
        cb.field(request.requestId())
                .field(request.customsNo())
                .field(request.enterpriseName())
                .field(request.productName())
                .field(request.validUntil() == null ? null : request.validUntil().toString())
                .number(request.totalQuantity())
                .field(request.at() == null ? null : request.at().toString());
        return sha256(cb.build());
    }

    /** 子批绑定指纹：操作 + 时刻 + 通关单号 + 批号 + 申报支数 + 期望版本。 */
    public static String customsBind(CustomsBindRequest request) {
        CanonicalBuilder cb = new CanonicalBuilder(CUSTOMS_BIND);
        cb.field(request.requestId())
                .field(request.customsNo())
                .field(request.batchNo())
                .number(request.declaredQuantity())
                .number(request.expectedVersion())
                .field(request.at() == null ? null : request.at().toString());
        return sha256(cb.build());
    }

    /** 通关单撤销指纹：操作 + 时刻 + 通关单号 + 期望版本。 */
    public static String customsRevoke(CustomsRevokeRequest request) {
        CanonicalBuilder cb = new CanonicalBuilder(CUSTOMS_REVOKE);
        cb.field(request.requestId())
                .field(request.customsNo())
                .number(request.expectedVersion())
                .field(request.at() == null ? null : request.at().toString());
        return sha256(cb.build());
    }

    /** 通关单修订指纹：操作 + 时刻 + 通关单号 + 新总支数 + 期望版本。 */
    public static String customsAmend(CustomsAmendRequest request) {
        CanonicalBuilder cb = new CanonicalBuilder(CUSTOMS_AMEND);
        cb.field(request.requestId())
                .field(request.customsNo())
                .number(request.newTotalQuantity())
                .number(request.expectedVersion())
                .field(request.at() == null ? null : request.at().toString());
        return sha256(cb.build());
    }

    /** 子批撤回指纹：操作 + 时刻 + 批号 + 期望版本。 */
    public static String subbatchWithdraw(SubbatchWithdrawRequest request) {
        CanonicalBuilder cb = new CanonicalBuilder(SUBBATCH_WITHDRAW);
        cb.field(request.requestId())
                .field(request.batchNo())
                .number(request.expectedVersion())
                .field(request.at() == null ? null : request.at().toString());
        return sha256(cb.build());
    }

    /**
     * 冷链证据提交指纹：操作 + 批号 + 期望版本 + 提交精确时刻
     * + 全片段（序号/起止精确时刻）+ 全读数（序号/精确时刻/温度）。
     * 温度按去尾零明文归一化，保证 6.5 与 6.50 指纹一致。
     */
    public static String coldchainSubmit(String batchNo,
                                         com.example.starter.dto.ColdChainSubmitRequest request) {
        CanonicalBuilder cb = new CanonicalBuilder(COLDCHAIN_SUBMIT);
        cb.field(request.requestId())
                .field(batchNo == null ? null : batchNo.trim())
                .number(request.expectedVersion())
                .field(request.submittedAt() == null ? null : request.submittedAt().toString());
        List<String> segments = request.segments() == null ? List.of() : request.segments().stream()
                .map(Fingerprints::segment)
                .toList();
        cb.list(segments);
        return sha256(cb.build());
    }

    private static String segment(com.example.starter.dto.SegmentInput segment) {
        CanonicalBuilder cb = new CanonicalBuilder("SEG");
        cb.field(segment.seq() == null ? null : segment.seq().toString())
                .field(segment.start() == null ? null : segment.start().toString())
                .field(segment.end() == null ? null : segment.end().toString());
        List<String> readings = segment.readings() == null ? List.of() : segment.readings().stream()
                .map(Fingerprints::reading)
                .toList();
        cb.list(readings);
        return cb.build();
    }

    private static String reading(com.example.starter.dto.ReadingInput reading) {
        CanonicalBuilder cb = new CanonicalBuilder("RDG");
        cb.field(reading.seq() == null ? null : reading.seq().toString())
                .field(reading.at() == null ? null : reading.at().toString())
                .field(reading.tempCelsius() == null
                        ? null : reading.tempCelsius().stripTrailingZeros().toPlainString());
        return cb.build();
    }

    /**
     * 结构化无歧义归一化构造器：每个字段写为 {@code <UTF-8 字节长度>:<内容>}，
     * 字段间以换行分隔；集合先写元素个数，再逐元素长度前缀。
     * 这样即使合法文本本身含 ":"、换行等分隔符也无法伪造字段边界。
     */
    static final class CanonicalBuilder {
        private final StringBuilder sb = new StringBuilder();

        CanonicalBuilder(String operation) {
            appendRaw(operation);
        }

        CanonicalBuilder field(String value) {
            sb.append('\n');
            appendRaw(value == null ? "" : value);
            return this;
        }

        CanonicalBuilder number(long value) {
            return field(Long.toString(value));
        }

        CanonicalBuilder list(List<String> values) {
            sb.append('\n').append("L").append(values.size());
            for (String value : values) {
                sb.append('\n');
                appendRaw(value == null ? "" : value);
            }
            return this;
        }

        String build() {
            return sb.toString();
        }

        private void appendRaw(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            sb.append(bytes.length).append(':').append(value);
        }
    }

    /** 暴露结构化编码供单元测试直接验证分隔符无歧义。 */
    static String encodeScalarForTest(String operation, String value) {
        return new CanonicalBuilder(operation).field(value).build();
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
