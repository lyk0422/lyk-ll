package com.example.starter.hazmat.po;

/**
 * 路线模板（合成基础数据）。
 *
 * @param routeCode 路线编码
 * @param routeName 路线名称
 * @param passLevel 路线通行等级（须不低于危化品类别要求等级方可核准）
 * @param forbidden 是否禁行路线（禁行一律驳回）
 */
public record RouteInfo(String routeCode, String routeName, int passLevel, boolean forbidden) {
}
