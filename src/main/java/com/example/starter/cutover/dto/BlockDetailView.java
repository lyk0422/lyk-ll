package com.example.starter.cutover.dto;

import java.util.List;

/**
 * 阻断明细视图：被依赖阻断的成员及其首个阻断来源集合。
 */
public record BlockDetailView(
        String batchId,
        String requestId,
        List<String> blockSources
) {
}
