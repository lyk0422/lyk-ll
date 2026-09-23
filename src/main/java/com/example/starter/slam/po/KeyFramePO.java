package com.example.starter.slam.po;

import java.time.Instant;

/**
 * 关键帧持久化对象。pose_json 为 [x, y, theta] 的 JSON 文本。
 *
 * @param id          全局唯一关键帧 ID
 * @param graphId     所属图 ID
 * @param source      数据来源
 * @param version     数据版本
 * @param poseJson    位姿测量 JSON（单位米/弧度）
 * @param contentHash 规范化内容摘要，用于同 ID 改参冲突检测
 * @param createdAt   创建时间（UTC）
 */
public record KeyFramePO(
        String id,
        String graphId,
        String source,
        long version,
        String poseJson,
        String contentHash,
        Instant createdAt
) {
}
