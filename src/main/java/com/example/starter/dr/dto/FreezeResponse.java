package com.example.starter.dr.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 冻结结算证据视图。
 *
 * @param freezeId        冻结头 ID
 * @param requestId       冻结请求幂等键
 * @param eventRef        事件业务编码
 * @param expectedVersion 冻结时的事件聚合版本
 * @param fingerprint     完整参数指纹
 * @param totalDelivered  总交付量
 * @param frozenAtUtc     冻结时刻 UTC
 * @param intervals       逐区间证据快照
 */
public record FreezeResponse(
        Long freezeId,
        String requestId,
        String eventRef,
        int expectedVersion,
        String fingerprint,
        BigDecimal totalDelivered,
        Instant frozenAtUtc,
        List<IntervalResult> intervals) {

    /**
     * 单个区间的冻结证据。
     */
    public record IntervalResult(
            String meterId,
            int intervalIdx,
            BigDecimal baselineValue,
            BigDecimal actualValue,
            int baselineVersion,
            int actualVersion,
            BigDecimal deliveredValue) {
    }
}
