package com.example.starter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 油机调度单。
 *
 * @param id            主键
 * @param requestId     调度请求幂等键
 * @param stationCode   基站编码
 * @param generatorCode 当前服务油机编号；发电异常待改派时为 null
 * @param status        调度单状态，见 {@link OrderStatus}
 * @param supportHours  派单时估算的电池可支撑时长，单位小时
 * @param deadlineAt    预计到达时限，UTC
 * @param assignedAt    当前油机分配时刻，UTC；未分配为 null
 * @param confirmedAt   发电确认时刻，UTC；未确认为 null
 * @param closedAt      结案时刻，UTC；未结案为 null
 * @param createdAt     创建时间，UTC
 * @param updatedAt     最后更新时间，UTC
 */
public record DispatchOrder(long id, String requestId, String stationCode, String generatorCode,
                            OrderStatus status, BigDecimal supportHours, Instant deadlineAt,
                            Instant assignedAt, Instant confirmedAt, Instant closedAt,
                            Instant createdAt, Instant updatedAt) {

    /**
     * 判断调度单是否未完成（在途、发电中或发电异常待改派）。
     */
    public boolean unfinished() {
        return status == OrderStatus.DISPATCHED
                || status == OrderStatus.GENERATING
                || status == OrderStatus.GENERATION_ABNORMAL;
    }
}
