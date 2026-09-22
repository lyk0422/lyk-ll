package com.example.starter.sync.web;

import jakarta.validation.constraints.NotNull;

/**
 * 融合帧提交请求。targetTs 为目标融合时刻(纪元毫秒)。
 */
public record CommitFrameRequest(@NotNull Long targetTs) {
}
