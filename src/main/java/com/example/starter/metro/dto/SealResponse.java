package com.example.starter.metro.dto;

/**
 * 日结封账响应。
 *
 * @param bizDate    业务日期
 * @param status     批次状态（SEALED）
 * @param totalCents 批次合计（分）
 * @param txnCount   批次内交易笔数
 * @param replayed   是否幂等重放（重复封账返回幂等成功）
 */
public record SealResponse(String bizDate, String status, long totalCents, int txnCount, boolean replayed)
        implements ReplayableResponse<SealResponse> {

    @Override
    public SealResponse asReplayed() {
        return new SealResponse(bizDate, status, totalCents, txnCount, true);
    }
}
