package com.example.starter.report;

import java.util.List;

/**
 * 执行回报与漏报标记对外 DTO 集合。时间字段均为 UTC ISO-8601 字符串。
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * 执行回报请求。
     *
     * @param windowCode  窗口编码
     * @param stationCode 地面站编码
     * @param status      回报状态：SUCCESS / FAILED / PARTIAL
     */
    public record ReportRequest(String windowCode, String stationCode, String status) {
    }

    /**
     * 回报处理结果。
     *
     * @param windowCode   窗口编码
     * @param stationCode  地面站编码
     * @param outcome      REPORTED=已回报 / DUPLICATE=重复回报 / LATE_RECORDED=迟到回报（漏报事实不变）
     * @param reportStatus 生效的回报状态；漏报场景为 null
     */
    public record ReportResult(String windowCode, String stationCode, String outcome,
                               String reportStatus) {
    }

    /**
     * 漏报标记请求。
     *
     * @param now 判定时刻（UTC ISO-8601），窗口结束时刻早于该时刻且未回报则标记漏报
     */
    public record MarkMissedRequest(String now) {
    }

    /**
     * 漏报标记结果。
     *
     * @param marked      本次新标记漏报的数量
     * @param windowCodes 本次新标记漏报的窗口编码
     */
    public record MarkMissedResult(int marked, List<String> windowCodes) {
    }
}
