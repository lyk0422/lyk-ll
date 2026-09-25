package com.example.starter.irrigation.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 计划发布幂等指纹：覆盖 requestId、轮次、组内全部斗口计划明细。
 * 明细按斗口编号升序规范化后参与摘要，保证同键同参指纹一致、字段顺序无关。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算发布请求指纹。
     *
     * @param requestId 幂等键
     * @param turnNo    轮次编号
     * @param groupCode 轮灌组编号
     * @param lines     斗口明细，方法内部按斗口编号排序规范化
     */
    public static String ofPlan(String requestId, long turnNo, String groupCode, List<PlanLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("指纹计算需要至少一条斗口明细");
        }
        StringBuilder canonical = new StringBuilder();
        canonical.append("requestId=").append(requestId).append('|')
                .append("turnNo=").append(turnNo).append('|')
                .append("group=").append(groupCode).append('|');
        lines.stream()
                .sorted(java.util.Comparator.comparing(PlanLine::outletCode))
                .forEach(line -> canonical.append(line.outletCode()).append(':')
                        .append(line.areaSquareMeter()).append(':')
                        .append(line.planCubicMeter()).append(':')
                        .append(line.carryoverCubicMeter()).append(';'));
        return sha256(canonical.toString());
    }

    /** 参与指纹规范化的斗口计划明细（含上轮结转补供量）。 */
    public record PlanLine(String outletCode, long areaSquareMeter, long planCubicMeter,
                           long carryoverCubicMeter) {}

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
