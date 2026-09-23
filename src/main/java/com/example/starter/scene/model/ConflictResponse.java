package com.example.starter.scene.model;

import java.util.List;

/**
 * 版本过期冲突（HTTP 409）的响应体。
 *
 * @param requestId         冲突请求的 ID（幂等参数冲突时同样返回）
 * @param currentVersion    服务端当前版本号
 * @param expectedVersion   调用方提交的期望版本号
 * @param changedObjects    自 expectedVersion 后发生过变化的对象 ID（含新增/删除/重命名/attach/detach）
 * @param changedJoints     自 expectedVersion 后发生过变化的关节名（含位姿无关）
 * @param reason            冲突原因：VERSION_MISMATCH / IDEMPOTENT_PARAM_MISMATCH
 */
public record ConflictResponse(
        String requestId,
        long currentVersion,
        Long expectedVersion,
        List<String> changedObjects,
        List<String> changedJoints,
        String reason) {
}
