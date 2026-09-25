package com.example.starter.feed.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 出栏申请结果。
 *
 * @param batchNo               养殖批次号
 * @param accepted              是否允许出栏
 * @param status                批次状态：ACTIVE / SLAUGHTERED
 * @param repeated              是否为出栏终态后的重复申请（幂等成功）
 * @param earliestSlaughterDate 最早合法出栏日（UTC）；休药期内被拒绝时回传
 * @param slaughteredAt         实际出栏时刻；尚未出栏为空
 * @param rejectReason          拒绝原因；接受时为空
 */
public record SlaughterView(
        String batchNo,
        boolean accepted,
        String status,
        boolean repeated,
        LocalDate earliestSlaughterDate,
        Instant slaughteredAt,
        String rejectReason) {
}
