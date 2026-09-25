package com.example.starter.hazmat.model;

/**
 * 路线途经点模板。
 *
 * @param seq           途经点序号，路线内从 1 递增
 * @param name          途经点名称
 * @param offsetMinutes 相对计划发运时刻的偏移分钟数
 */
public record RouteWaypoint(int seq, String name, int offsetMinutes) {
}
