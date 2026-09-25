package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.CardStatus;

/**
 * 状态变更动作（初审/撤回/归档）的响应。
 *
 * @param cardNo  卡片编号
 * @param status  动作后卡片状态
 * @param message 结果说明
 */
public record ActionResponse(String cardNo, CardStatus status, String message) {
}
