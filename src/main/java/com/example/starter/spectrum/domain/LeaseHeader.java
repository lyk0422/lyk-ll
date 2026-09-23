package com.example.starter.spectrum.domain;

/**
 * 租约头（lease 表一行）：身份、当前版本与取消状态。
 *
 * @param leaseId        租约 ID
 * @param currentVersion 当前版本号，从 1 开始
 * @param status         ACTIVE 或 CANCELLED
 * @param createTime     创建时间 epoch 毫秒 UTC
 * @param updateTime     最近变更时间 epoch 毫秒 UTC
 * @param cancelTime     取消生效时间 epoch 毫秒 UTC；{@code null} 表示未取消
 */
public record LeaseHeader(long leaseId,
                          int currentVersion,
                          LeaseStatus status,
                          long createTime,
                          long updateTime,
                          Long cancelTime) {
}
