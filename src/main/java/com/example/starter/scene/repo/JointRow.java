package com.example.starter.scene.repo;

/**
 * 关节当前状态行。
 *
 * @param name 关节名
 * @param position 当前位置，单位弧度
 * @param minLimit 位置下限，单位弧度
 * @param maxLimit 位置上限，单位弧度
 */
public record JointRow(String name, double position, double minLimit, double maxLimit) {
}
