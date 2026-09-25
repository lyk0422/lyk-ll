package com.example.starter.hazmat.po;

/**
 * 路线模板途经点定义。
 *
 * @param routeCode      所属路线编码
 * @param seq            途经点序号（从 0 起）
 * @param name           途经点名称
 * @param offsetMinutes  相对计划发运时刻的偏移（分钟）；计划通过时刻 = 计划发运 + 偏移
 */
public record RouteWaypointDef(String routeCode, int seq, String name, int offsetMinutes) {
}
