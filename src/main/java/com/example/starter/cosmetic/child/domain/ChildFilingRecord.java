package com.example.starter.cosmetic.child.domain;

import java.time.Instant;

/**
 * 儿童备案聚合（持久化对象的内存镜像，字段口径与 child_filing 表一致）。
 *
 * <p>versionNo 为乐观锁版本：从 1 开始，每次成功修改 +1；
 * 修改请求须携带 expectedVersion，不一致则业务拒绝（422）。</p>
 *
 * @param id                主键
 * @param filingNo          儿童备案编号
 * @param productCode       产品编码（唯一）
 * @param productName       产品名称
 * @param versionNo         聚合版本（乐观锁）
 * @param status            聚合状态
 * @param currentRevisionNo 当前最新修订号（公示只能使用该修订）
 * @param requestId         创建立案的幂等键
 * @param createdAtUtc      创建时刻(UTC)
 * @param updatedAtUtc      最近更新时刻(UTC)
 */
public record ChildFilingRecord(Long id,
                                String filingNo,
                                String productCode,
                                String productName,
                                int versionNo,
                                ChildFilingStatus status,
                                int currentRevisionNo,
                                String requestId,
                                Instant createdAtUtc,
                                Instant updatedAtUtc) {
}
