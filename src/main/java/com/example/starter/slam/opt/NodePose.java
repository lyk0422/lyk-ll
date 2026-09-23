package com.example.starter.slam.opt;

/**
 * 优化器输入中的节点先验位姿。
 */
public record NodePose(String keyframeId, double x, double y, double theta) {
}
