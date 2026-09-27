package com.example.starter.api.dto;

import java.util.List;

/**
 * 连续水位线视图：水位线只推进到连续完整前缀；缺号后的包保持缓冲，
 * 不参与自检、互检或见证放行。
 */
public record AcquisitionWatermarkView(
        String sessionNo,
        int watermark,
        long version,
        List<Integer> bufferedSeqs,
        int committedCount,
        int lateCount) {
}
