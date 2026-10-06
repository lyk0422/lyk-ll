package com.example.starter.model;

/**
 * 索道线路行记录。
 *
 * @param id     线路 id
 * @param code   线路编码
 * @param name   线路名称
 * @param status 线路状态
 */
public record WindLine(long id, String code, String name, LineStatus status) {
}
