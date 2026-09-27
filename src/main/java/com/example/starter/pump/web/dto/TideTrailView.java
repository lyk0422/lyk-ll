package com.example.starter.pump.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分区工况轨迹查询视图：每次成功切换追加一条，版本单调递增。
 *
 * @param zoneCode 流域分区代码
 * @param items    轨迹条目，按发生顺序排序
 */
public record TideTrailView(
        String zoneCode,
        List<TrailItem> items
) {

    /** 工况轨迹条目。 */
    public record TrailItem(Long id, String fromMode, String toMode, int version,
                            Long candidateId, String requestId, LocalDateTime switchedAt) {
    }
}
