package com.example.starter.planningscene.dto;

import java.util.List;

/**
 * 409 冲突响应体：版本过期或幂等键参数不一致时返回。
 *
 * @param reason VERSION_CONFLICT（版本过期）或 IDEMPOTENCY_CONFLICT（同键异参）
 * @param currentVersion 服务端当前版本
 * @param expectedVersion 客户端请求中携带的期望版本（幂等冲突时为首次请求的期望版本）
 * @param changedObjectIds 自 expectedVersion 之后发生变化（含删除）的对象 ID
 * @param changedJointNames 自 expectedVersion 之后发生变化的关节名
 */
public record ConflictResponse(String reason, long currentVersion, long expectedVersion,
                               List<String> changedObjectIds, List<String> changedJointNames) {
}
