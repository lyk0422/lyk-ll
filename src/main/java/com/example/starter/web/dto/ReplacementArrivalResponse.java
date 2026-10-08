package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 热备新车到场登记响应。outcome：ARRIVED已登记/ALL_ARRIVED新车全部到齐（仍待双操作员确认切换）。
 *
 * @param replacementId 接替单ID
 * @param truckId       到场车辆ID
 * @param outcome       登记结果
 * @param status        接替单当前状态
 * @param version       接替单当前版本号
 * @param arrivedAt     到场时刻（本地时间）
 */
public record ReplacementArrivalResponse(long replacementId, long truckId, String outcome, String status,
        int version, LocalDateTime arrivedAt) {
}
