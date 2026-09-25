package com.example.starter.metro.dto;

/**
 * 进闸响应。
 *
 * @param txnNo    交易流水号
 * @param status   交易状态（ENTERED）
 * @param replayed 是否幂等重放命中首次结果
 */
public record EntryResponse(String txnNo, String status, boolean replayed)
        implements ReplayableResponse<EntryResponse> {

    @Override
    public EntryResponse asReplayed() {
        return new EntryResponse(txnNo, status, true);
    }
}
