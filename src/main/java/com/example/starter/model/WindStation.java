package com.example.starter.model;

/**
 * 线路站点行记录。
 *
 * @param id     站点 id
 * @param lineId 所属线路 id
 * @param seqNo  站点顺序号
 * @param name   站点名称
 */
public record WindStation(long id, long lineId, int seqNo, String name) {
}
