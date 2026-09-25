package com.example.starter.legality.api;

import java.util.List;

/**
 * 单条报到结果。
 *
 * @param requestId    请求ID
 * @param batchId      所属批次ID，可能为 {@code null}
 * @param crewId       机组成员ID
 * @param flightNo     航班号
 * @param legal        合规判定
 * @param reasons      违规原因码列表，空列表表示合法
 * @param delayMinutes 已受理延误延长累计，单位分钟
 * @param replayed     是否命中同键重放（返回首次判定）
 */
public record ReportResponse(
        String requestId,
        String batchId,
        String crewId,
        String flightNo,
        boolean legal,
        List<String> reasons,
        int delayMinutes,
        boolean replayed) {
}
