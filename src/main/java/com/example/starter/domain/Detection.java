package com.example.starter.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 单帧检测输入。
 *
 * @param detectionId 检测唯一标识；重复提交按幂等处理
 * @param timestamp   检测时戳，epoch 毫秒（UTC）
 * @param source      检测来源（传感器/模型标识，合成场景可为任意字符串）
 * @param category    目标类别
 * @param x           位置 X（米）
 * @param y           位置 Y（米）
 * @param z           位置 Z（米，无高度信息可传 0）
 * @param confidence  检测置信度 [0,1]，低于门限将被拒绝
 */
public record Detection(
        @NotBlank String detectionId,
        @NotNull Long timestamp,
        @NotBlank String source,
        @NotBlank String category,
        @NotNull Double x,
        @NotNull Double y,
        @NotNull Double z,
        @NotNull @PositiveOrZero Double confidence) {
}
