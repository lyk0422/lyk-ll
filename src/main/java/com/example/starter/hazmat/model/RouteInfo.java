package com.example.starter.hazmat.model;

/**
 * 运输路线主数据。
 *
 * @param id        路线主键
 * @param code      路线编码，全局唯一
 * @param name      路线名称
 * @param passLevel 路线通行等级，危化品等级须小于等于该等级
 */
public record RouteInfo(long id, String code, String name, int passLevel) {
}
