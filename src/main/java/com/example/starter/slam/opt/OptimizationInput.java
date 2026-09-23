package com.example.starter.slam.opt;

import java.util.List;

/**
 * 优化器的确定性输入：某图版本下全部有效关键帧与有效边的不可变快照。
 */
public record OptimizationInput(
        int graphVersion,
        String snapshotDigest,
        List<NodePose> keyframes,
        List<EdgeConstraint> edges
) {
}
