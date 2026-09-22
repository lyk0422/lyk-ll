package com.example.starter.costmap.model;

/**
 * 某个格点上当前生效障碍物的确定性合并赢家。
 * 决胜顺序：priority 降序、timestampMs 降序、sourceId 字典序升序。
 *
 * @param sourceId    赢家中标来源ID
 * @param priority    赢家优先级
 * @param timestampMs 赢家事件时间戳，epoch 毫秒
 * @param requestId   赢家请求ID
 */
public record CellWinner(String sourceId, int priority, long timestampMs, String requestId) {
}
