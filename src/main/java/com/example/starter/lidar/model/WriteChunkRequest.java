package com.example.starter.lidar.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * 写入点云块的请求。
 *
 * @param chunkId     块唯一标识；同 ID 重复写入走幂等/冲突判定
 * @param version     块版本号，查询结果中原样返回实际读取块的版本（非负）
 * @param source      数据来源标识，查询结果中原样返回
 * @param compression 请求的压缩方式；GZIP 不满足收益时服务端回退 NONE
 * @param points      块内点记录，顺序即块内点序，参与稳定排序
 */
public record WriteChunkRequest(
        @NotBlank String chunkId,
        @PositiveOrZero long version,
        @NotBlank String source,
        @NotNull Compression compression,
        @NotEmpty @Valid List<@NotNull PointDatum> points
) {
}
