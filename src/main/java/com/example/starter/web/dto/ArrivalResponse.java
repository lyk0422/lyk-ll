package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 到场确认响应。outcome：ARRIVED已记录/ALL_ARRIVED全部到场转供电/LATE迟到仅记事件。
 *
 * @param dispatchId 调度单ID
 * @param truckId    车辆ID
 * @param outcome    到场结果
 * @param status     调度单当前状态
 * @param version    调度单当前版本号（迟到事件不变更版本，返回当前版本）
 * @param arrivedAt  到场时刻（本地时间）
 */
public record ArrivalResponse(long dispatchId, long truckId, String outcome, String status, int version,
        LocalDateTime arrivedAt) {
}
