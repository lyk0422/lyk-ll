package com.example.starter.ili;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹：对 requestId、管段、两回合标识与全部缺陷读数做规范化摘要（SHA-256）。
 * 缺陷清单按缺陷编号排序后参与摘要，数值经过去尾零规范化，保证同参同指纹。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算导入请求的规范化指纹（SHA-256 十六进制，64 字符）。
     */
    public static String of(Dtos.ImportAlignmentRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("requestId=").append(request.requestId()).append('\n');
        canonical.append("segment=").append(request.segmentCode()).append('\n');
        appendRun(canonical, "run1", request.run1());
        appendRun(canonical, "run2", request.run2());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private static void appendRun(StringBuilder canonical, String label, Dtos.RunPayload run) {
        canonical.append(label).append('=').append(run.runCode()).append('@').append(run.runDate())
                .append('\n');
        List<Dtos.DefectPayload> sorted = run.defects().stream()
                .sorted(Comparator.comparing(Dtos.DefectPayload::defectRef))
                .toList();
        for (Dtos.DefectPayload defect : sorted) {
            canonical.append(defect.defectRef()).append('|')
                    .append(normalize(defect.absMileageM())).append('|')
                    .append(normalize(defect.depthPct())).append('\n');
        }
    }

    /** 数值规范化：去尾零后按 plain 字符串输出，避免 0.5 与 0.50 产生不同指纹。 */
    private static String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
