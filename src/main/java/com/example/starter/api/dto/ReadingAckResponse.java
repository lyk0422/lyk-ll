package com.example.starter.api.dto;

/**
 * 读数上报结果：inserted 为实际落库条数，duplicated 为命中“测点＋采样时刻”去重条数，
 * late 为封存后到达仅登记迟到事件的条数。
 */
public record ReadingAckResponse(int received, int inserted, int duplicated, int late) {
}
