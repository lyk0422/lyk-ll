package com.example.starter.flood.web.dto;

/**
 * 入库洪水过程线录入结果视图。
 *
 * @param warningNo 处置链预警编号
 * @param acceptedPoints 本次受理点数
 * @param totalPoints 该处置链过程线总点数
 * @param replay 是否幂等重放
 */
public record HydrographView(
        String warningNo,
        int acceptedPoints,
        int totalPoints,
        boolean replay) {
}
