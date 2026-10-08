package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 检修单登记/修改响应。
 *
 * @param id         检修单 id
 * @param lineId     绑定线路 id
 * @param version    当前版本（新建为 1，修改成功后加 1）
 * @param status     检修单状态
 * @param startAt    窗口起点（UTC，含）
 * @param endAt      窗口终点（UTC，不含）
 * @param reason     检修原因
 * @param segmentIds 涉及分段清单（按顺序号升序）
 */
public record MaintenanceOrderResponse(long id, long lineId, int version, String status,
                                       Instant startAt, Instant endAt, String reason,
                                       List<Long> segmentIds) {
}
