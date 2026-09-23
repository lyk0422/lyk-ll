package com.example.starter.slam.optim;

import java.util.List;

/**
 * 优化输入中单个关键帧的不可变快照。
 *
 * @param id      关键帧 ID
 * @param source  数据来源
 * @param version 数据版本
 * @param pose    初始位姿 [x, y, theta]，单位米/弧度
 */
public record FrameSnapshot(
        String id,
        String source,
        long version,
        List<Double> pose
) {
}
