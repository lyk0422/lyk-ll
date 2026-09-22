package com.example.starter.map;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 地图版本视图：版本元信息 + 全量路段快照。版本发布后内容不可变。
 *
 * @param versionId  地图版本业务标识
 * @param status     版本状态
 * @param segments   版本内全部路段
 * @param createdAt  发布时间（Asia/Shanghai）
 */
public record MapVersionView(
        String versionId,
        MapVersionStatus status,
        List<MapSegment> segments,
        LocalDateTime createdAt) {
}
