package com.example.starter.ctlog.dto;

/**
 * 观察者上报处理结果。
 *
 * @param accepted    是否接受（较小树头被拒，签名无效被拒）
 * @param alertCreated 本次上报是否原子创建了新的 SPLIT_VIEW 告警
 * @param latestTreeSize 该观察者当前最新位置
 */
public record ObserveResponse(boolean accepted, boolean alertCreated, long latestTreeSize) {
}
