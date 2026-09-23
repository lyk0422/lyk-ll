package com.example.starter.scene.model;

import jakarta.validation.constraints.NotNull;

/**
 * 单个附着物操作。
 *
 * @param op       操作类型
 * @param objectId 目标碰撞对象 ID
 * @param linkName ATTACH 时的目标机器人 Link；DETACH 时忽略
 * @param frameId  DETACH 时对象放回世界集合使用的参考坐标系；ATTACH 时忽略
 */
public record AttachmentCommand(
        @NotNull AttachmentOp op,
        String objectId,
        String linkName,
        String frameId) {
}
