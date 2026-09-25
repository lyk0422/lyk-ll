package com.example.starter.marpol.model;

import com.example.starter.marpol.domain.VoyageStatus;

import java.time.Instant;

/**
 * 航次（合成数据）。
 *
 * @param status OPEN=开放可记录，CLOSED=已关闭不可新增
 * @param closedAt 关闭时刻（UTC），空表示未关闭
 */
public record Voyage(Long id, Long shipId, String voyageNo, VoyageStatus status,
                     Instant closedAt, Instant createdAt) {
}
