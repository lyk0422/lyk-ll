package com.example.starter.metro.dto.refund;

import com.example.starter.metro.dto.ReplayableResponse;

/**
 * 票卡关闭响应。
 *
 * @param cardId   票卡编号
 * @param closed   是否已处于关闭状态
 * @param replayed 是否幂等重放命中首次结果
 */
public record CardCloseResponse(String cardId, boolean closed, boolean replayed)
        implements ReplayableResponse<CardCloseResponse> {

    @Override
    public CardCloseResponse asReplayed() {
        return new CardCloseResponse(cardId, closed, true);
    }
}
