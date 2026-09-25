package com.example.starter.domain;

/**
 * 机组行。availableKw 为最新可用出力（kW），由执行回报刷新。
 */
public record TurbineRow(long id, long farmId, String code, int capacityKw, int availableKw) {

    /**
     * 单机最低技术出力：额定容量 15% 向下取整，单位 kW。
     */
    public int minKw() {
        return capacityKw * 15 / 100;
    }
}
