package com.example.starter.payment;

import java.time.Instant;

/**
 * 支付交易。
 *
 * @param id          交易ID
 * @param requestId   入账请求幂等键
 * @param fingerprint 幂等指纹（requestId+流水号+金额+商户号 哈希）
 * @param serialNo    渠道流水号，全局唯一
 * @param merchantNo  商户号
 * @param amount      交易金额，单位分
 * @param fee         手续费，单位分
 * @param txnTime     交易时间（UTC）
 * @param batchId     归属批次ID；挂账时为 null
 * @param status      状态：POSTED 已入账 / SUSPENDED 挂账
 */
public record PayTransaction(long id, String requestId, String fingerprint, String serialNo,
                             String merchantNo, long amount, long fee, Instant txnTime,
                             Long batchId, String status) {

    public static final String POSTED = "POSTED";
    public static final String SUSPENDED = "SUSPENDED";
}
