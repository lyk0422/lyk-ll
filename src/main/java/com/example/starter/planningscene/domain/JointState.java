package com.example.starter.planningscene.domain;

/**
 * 单个关节在某一版本下的状态。
 *
 * @param name 关节名（机器人模型内唯一）
 * @param position 关节位置（转动关节为弧度，平动关节为米）
 * @param lowerBound 关节下限（单位同 position）
 * @param upperBound 关节上限（单位同 position）
 */
public record JointState(String name, double position, double lowerBound, double upperBound) {
}
