package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 交班响应视图。
 *
 * @param status         交班状态：OPEN 未结束 / SWITCHED 已切换 / WITHDRAWN 已撤回 / CLOSED 核销关闭
 * @param outComment     出班方签认意见，未签认为 null
 * @param inComment      接班方签认意见，未签认为 null
 * @param preOccupyStart 接班预占起点（仅 OPEN 状态非空）
 * @param preOccupyEnd   接班预占终点（仅 OPEN 状态非空）
 * @param version        交班乐观锁版本，1 起，每次成功修改加 1
 */
public record HandoverResponse(
        Long handoverId,
        Long planId,
        String status,
        Long outPilotId,
        Long inPilotId,
        LocalDateTime handoverAt,
        boolean outAck,
        String outComment,
        boolean inAck,
        String inComment,
        LocalDateTime preOccupyStart,
        LocalDateTime preOccupyEnd,
        int version) {
}
