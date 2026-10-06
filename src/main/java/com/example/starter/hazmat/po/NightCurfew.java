package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 夜间禁行时段规则（半开区间 [curfewStart, curfewEnd)）。
 *
 * @param id          主键
 * @param routeCode   适用路线编码
 * @param curfewStart 禁行起始时刻（Asia/Shanghai），恰满起点即判禁行
 * @param curfewEnd   禁行结束时刻（Asia/Shanghai），等于结束时刻视为可通行
 */
public record NightCurfew(
        long id,
        String routeCode,
        LocalDateTime curfewStart,
        LocalDateTime curfewEnd) {
}
