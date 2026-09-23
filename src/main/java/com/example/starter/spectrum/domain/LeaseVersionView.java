package com.example.starter.spectrum.domain;

import java.util.SortedSet;

/**
 * 租约的一个历史版本（lease_version + lease_cell），永久保留。
 *
 * @param leaseId   租约 ID
 * @param version   版本号
 * @param spec      版本规格
 * @param requestId 产生该版本的请求幂等键
 * @param createdAt 版本写入时间 epoch 毫秒 UTC
 * @param cells     该版本的区域单元集合
 */
public record LeaseVersionView(long leaseId,
                               int version,
                               LeaseSpec spec,
                               String requestId,
                               long createdAt,
                               SortedSet<String> cells) {
}
