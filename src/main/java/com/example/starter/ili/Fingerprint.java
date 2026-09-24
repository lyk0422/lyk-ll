package com.example.starter.ili;

import com.example.starter.ili.api.ImportBatchRequest;
import com.example.starter.ili.api.ReadingPayload;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 请求指纹：覆盖 requestId、管段、两回合标识与全部缺陷读数的规范化摘要（SHA-256）。
 * 读数按键排序、数值去尾零后参与摘要，保证语义相同的请求得到相同指纹。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算导入请求的规范化指纹（小写十六进制 64 字符）。
     */
    public static String of(ImportBatchRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("req=").append(req.requestId()).append('\n');
        sb.append("segment=").append(req.segmentId()).append('\n');
        appendRun(sb, "OLD", req.oldRun().runId(), req.oldRun().inspectedAt().toString(), req.oldRun().readings());
        appendRun(sb, "NEW", req.newRun().runId(), req.newRun().inspectedAt().toString(), req.newRun().readings());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void appendRun(StringBuilder sb, String side, String runId, String inspectedAt,
                                  List<ReadingPayload> readings) {
        sb.append(side).append(".run=").append(runId).append('\n');
        sb.append(side).append(".at=").append(inspectedAt).append('\n');
        String normalized = readings.stream()
                .sorted(Comparator.comparing(ReadingPayload::readingKey))
                .map(r -> r.readingKey() + '=' + norm(r.stationM()) + ',' + norm(r.depthPct()))
                .collect(Collectors.joining(";"));
        sb.append(side).append(".readings=").append(normalized).append('\n');
    }

    private static String norm(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
