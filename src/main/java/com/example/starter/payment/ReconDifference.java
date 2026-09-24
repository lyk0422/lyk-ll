package com.example.starter.payment;

import java.time.Instant;

/**
 * 对账差异记录。
 *
 * @param id           差异ID
 * @param batchId      对账批次ID
 * @param serialNo     渠道流水号
 * @param diffType     差异类型：LOCAL_EXCESS 本地多账 / CHANNEL_EXCESS 渠道多账 / AMOUNT_MISMATCH 金额差错
 * @param localAmount  本地金额（分），渠道多账时为 null
 * @param channelAmount 渠道金额（分），本地多账时为 null
 * @param status       状态：OPEN 待处理 / RESOLVED 已处理
 * @param resolution   处理方式：WRITE_OFF 核销 / REPOST 补记 / REFUND 退款；未处理为 null
 * @param resolvedBy   处理人；未处理为 null
 * @param resolvedAt   处理时间（UTC）；未处理为 null
 */
public record ReconDifference(long id, long batchId, String serialNo, String diffType,
                              Long localAmount, Long channelAmount, String status,
                              String resolution, String resolvedBy, Instant resolvedAt) {

    public static final String LOCAL_EXCESS = "LOCAL_EXCESS";
    public static final String CHANNEL_EXCESS = "CHANNEL_EXCESS";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";

    public static final String WRITE_OFF = "WRITE_OFF";
    public static final String REPOST = "REPOST";
    public static final String REFUND = "REFUND";
}
