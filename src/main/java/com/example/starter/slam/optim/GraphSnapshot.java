package com.example.starter.slam.optim;

import java.util.List;

/**
 * 优化任务执行时固定的图输入快照。重试必须复用同一快照。
 *
 * @param graphId      图 ID
 * @param graphVersion 快照对应的有效图版本
 * @param frames       全部关键帧快照（按 ID 排序）
 * @param edges        全部有效边快照（按 ID 排序，不含 REJECTED 回环）
 */
public record GraphSnapshot(
        String graphId,
        long graphVersion,
        List<FrameSnapshot> frames,
        List<EdgeSnapshot> edges
) {

    /**
     * 生成可持久化的快照摘要，用于失败审计与任务展示。
     *
     * @return 摘要字符串
     */
    public String summary() {
        return "graph=" + graphId
                + ",version=" + graphVersion
                + ",frames=" + frames.size()
                + ",edges=" + edges.size();
    }
}
