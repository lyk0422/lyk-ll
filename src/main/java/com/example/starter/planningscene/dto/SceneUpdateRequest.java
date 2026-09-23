package com.example.starter.planningscene.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Planning Scene 批量提交请求：一个请求内可同时更新关节、机器人姿态并执行多个对象操作。
 *
 * @param requestId 客户端幂等键；同键同参返回原结果，同键异参返回 409
 * @param expectedVersion 客户端所基于的版本号；与当前版本不一致时整批拒绝（409）
 * @param robotPose 新的机器人根姿态；为 null 表示本批不修改
 * @param jointUpdates 关节赋值列表；为空表示不修改关节
 * @param operations 对象操作列表；为空表示仅更新关节/姿态
 */
public record SceneUpdateRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @Valid PoseDto robotPose,
        @Valid List<JointValueDto> jointUpdates,
        @Valid List<ObjectOperationDto> operations) {
}
