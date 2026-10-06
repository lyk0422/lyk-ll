package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 夜间禁行时段（合成管制规则）。半开区间 [curfewStart, curfewEnd)，
 * 计划或实际时刻恰等于起始时刻即视为进入禁行（恰满禁行起点判定）。
 *
 * @param id          主键
 * @param routeCode   适用路线编码
 * @param curfewStart 禁行起始时刻，Asia/Shanghai
 * @param curfewEnd   禁行结束时刻，Asia/Shanghai
 */
public record NightCurfew(
        long id,
        String routeCode,
        LocalDateTime curfewStart,
        LocalDateTime curfewEnd) {
}
