package com.example.starter.scene.dto;

import java.util.List;

/**
 * 一次 Planning Scene 批量修改请求。携带幂等键与期望版本，全部操作原子生效。
 *
 * @param requestId 幂等请求 ID；同键同参返回原结果，同键异参返回 409
 * @param expectedVersion 期望的当前场景版本；不一致时返回 409 与差异列表
 * @param jointUpdates 关节位置更新列表，同一批内关节名唯一
 * @param objectOps 碰撞对象新增/修改/删除/重命名操作列表，同一批内对象 ID 唯一
 * @param attachOps 附着/解除附着操作列表
 */
public record ApplyRequest(String requestId,
                           Long expectedVersion,
                           List<JointUpdate> jointUpdates,
                           List<ObjectOp> objectOps,
                           List<AttachOp> attachOps) {
}
