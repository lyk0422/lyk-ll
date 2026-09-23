package com.example.starter.pointcloud.api;

import java.util.List;

/**
 * 空间查询响应。
 *
 * @param points       命中点，按（时间戳, chunkId, 块内点序）稳定排序
 * @param quarantined  本次查询读取时校验失败并被隔离的块 ID 列表
 */
public record QueryResponse(List<QueryHitPoint> points,
                            List<QuarantinedChunk> quarantined) {

    /**
     * 被隔离块信息。
     *
     * @param chunkId  块 ID
     * @param fileName 原块文件名
     * @param reason   隔离原因
     */
    public record QuarantinedChunk(String chunkId, String fileName, String reason) {
    }
}
