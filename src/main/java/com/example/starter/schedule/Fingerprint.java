package com.example.starter.schedule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段，SHA-256 十六进制。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /** 编制/修订排产指纹。 */
    public static String ofSchedule(String requestId, String lineCode, String unitNo,
                                    List<ScheduleRequests.ItemSpec> items,
                                    List<ScheduleRequests.RollWindowSpec> windows) {
        StringJoiner body = new StringJoiner("|");
        body.add("schedule").add(requestId).add(lineCode).add(unitNo);
        for (ScheduleRequests.ItemSpec item : items) {
            body.add("item[" + item.seq() + "," + item.contractNo() + "," + item.widthMm() + ","
                    + item.mileageKm().stripTrailingZeros().toPlainString() + "," + item.warmup() + ","
                    + item.plannedStart() + "," + item.plannedEnd() + "]");
        }
        if (windows != null) {
            for (ScheduleRequests.RollWindowSpec w : windows) {
                body.add("window[" + w.start() + "," + w.end() + "]");
            }
        }
        return sha256(body.toString());
    }

    /** 实绩回传指纹。 */
    public static String ofActuals(String requestId, long scheduleId,
                                   List<ScheduleRequests.ActualItem> actuals) {
        StringJoiner body = new StringJoiner("|");
        body.add("actuals").add(requestId).add(String.valueOf(scheduleId));
        for (ScheduleRequests.ActualItem a : actuals) {
            body.add("actual[" + a.contractNo() + "," + a.passNo() + ","
                    + a.mileageKm().stripTrailingZeros().toPlainString() + "," + a.occurredAt() + "]");
        }
        return sha256(body.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
