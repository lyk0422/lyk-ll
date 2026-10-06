package com.example.starter.web.dto;

/**
 * 线路视图。
 *
 * @param id            线路 id
 * @param code          线路编码
 * @param name          线路名称
 * @param status        线路状态
 * @param activeEventId 进行中管制事件 id，无为 null
 */
public record LineView(long id, String code, String name, String status, Long activeEventId) {
}
