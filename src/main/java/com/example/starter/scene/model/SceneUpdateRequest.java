package com.example.starter.scene.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Planning Scene 批量修改请求。
 * <p>一次请求可同时包含对象增改删/重命名、关节更新、机器人姿态更新和 attach/detach；
 * 任一操作非法则整批回滚。
 *
 * @param requestId        调用方生成的请求唯一 ID，用于幂等去重
 * @param expectedVersion  调用方基于的场景版本；过期返回 409
 * @param objects          碰撞对象操作列表，可为空
 * @param joints           关节更新列表，可为空
 * @param robotPose        新的机器人基座位姿，为空表示不更新
 * @param attachments      附着物操作列表，可为空
 */
public record SceneUpdateRequest(
        @NotBlank String requestId,
        long expectedVersion,
        @Valid List<ObjectCommand> objects,
        @Valid List<JointUpdate> joints,
        @Valid RobotPose robotPose,
        @Valid List<AttachmentCommand> attachments) {
}
