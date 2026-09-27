package com.example.starter.metro.dto;

/**
 * 票卡关闭响应。
 *
 * @param cardId   票卡编号
 * @param status   票卡状态（CLOSED）
 * @param version  关闭后版本
 * @param replayed 是否幂等重放命中首次结果
 */
public record CardCloseResponse(String cardId, String status, int version, boolean replayed)
        implements ReplayableResponse<CardCloseResponse> {

    @Override
    public CardCloseResponse asReplayed() {
        return new CardCloseResponse(cardId, status, version, true);
    }
}
