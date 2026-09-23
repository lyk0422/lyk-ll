package com.example.starter.pointcloud.api;

import com.example.starter.pointcloud.model.Compression;
import com.example.starter.pointcloud.model.Point;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * 写入块请求。
 *
 * @param chunkId     块唯一标识，非空
 * @param version     块版本，非负
 * @param source      数据来源，非空
 * @param compression 请求的压缩方式；实际落盘可能因回退为 NONE
 * @param points      点列表，非空；点在列表中的顺序即块内点序
 */
public record WriteChunkRequest(
        @NotBlank String chunkId,
        @PositiveOrZero long version,
        @NotBlank String source,
        @NotNull Compression compression,
        @NotEmpty List<@NotNull Point> points) {
}
