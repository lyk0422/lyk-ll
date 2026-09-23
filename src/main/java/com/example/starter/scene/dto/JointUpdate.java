package com.example.starter.scene.dto;

/**
 * 单个关节位置更新。
 *
 * @param name 关节名，必须已存在
 * @param position 目标位置，单位弧度，必须有限且位于关节限位内（含边界）
 */
public record JointUpdate(String name, double position) {
}
