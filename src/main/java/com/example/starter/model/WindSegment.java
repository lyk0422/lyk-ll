package com.example.starter.model;

/**
 * 线路分段行记录（相邻两站点之间的监测段）。
 *
 * @param id            分段 id
 * @param lineId        所属线路 id
 * @param seqNo         分段顺序号
 * @param fromStationId 起点站 id
 * @param toStationId   终点站 id
 */
public record WindSegment(long id, long lineId, int seqNo, long fromStationId, long toStationId) {
}
