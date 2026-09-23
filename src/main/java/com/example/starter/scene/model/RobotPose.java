package com.example.starter.scene.model;

import jakarta.validation.constraints.NotNull;

/**
 * 机器人基座在 world 坐标系下的位姿；位置单位米，姿态为单位四元数（x,y,z,w）。
 *
 * @param x  位置 x（米）
 * @param y  位置 y（米）
 * @param z  位置 z（米）
 * @param qx 四元数 x 分量
 * @param qy 四元数 y 分量
 * @param qz 四元数 z 分量
 * @param qw 四元数 w 分量
 */
public record RobotPose(
        @NotNull Double x,
        @NotNull Double y,
        @NotNull Double z,
        @NotNull Double qx,
        @NotNull Double qy,
        @NotNull Double qz,
        @NotNull Double qw) {
}
