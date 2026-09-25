package com.example.starter.blast.api;

import java.time.Instant;

/**
 * 振动读数回传结果。
 *
 * @param accepted   是否为首次接收并落库；false 表示命中“爆区＋测点＋采样时刻”去重
 * @param duplicate  是否重复读数（回传齐全前的重复回传）
 * @param lateEvent  是否为封档后的迟到读数（仅记录迟到事件，不改结论）
 * @param overLimit  该读数是否超过测点备案阈值
 * @param frozen     本次回传后矿区是否处于冻结状态
 * @param sampleTime 采样时刻（UTC）
 */
public record ReadingResult(
        boolean accepted,
        boolean duplicate,
        boolean lateEvent,
        boolean overLimit,
        boolean frozen,
        Instant sampleTime) {
}
