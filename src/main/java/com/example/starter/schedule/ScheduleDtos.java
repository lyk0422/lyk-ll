package com.example.starter.schedule;

import java.util.List;

/**
 * 编排服务对外 DTO 集合。时间字段均为 UTC ISO-8601 字符串。
 */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    /**
     * 编排申请。
     *
     * @param requestId     幂等键
     * @param satelliteCode 卫星编码
     * @param stationCode   地面站编码
     * @param windowCode    过境窗口编码
     * @param priority      任务优先级 1-5，5 最高
     */
    public record SubmitRequest(String requestId, String satelliteCode, String stationCode,
                                String windowCode, Integer priority) {
    }

    /**
     * 被挤占条目及重排建议。
     *
     * @param entryId              被挤占的编排条目ID
     * @param requestId            被挤占条目的来源申请
     * @param windowCode           被挤占窗口编码
     * @param suggestedWindowCodes 重排建议窗口编码列表
     */
    public record PreemptedInfo(long entryId, String requestId, String windowCode,
                                List<String> suggestedWindowCodes) {
    }

    /**
     * 编排申请受理结果。
     *
     * @param requestId 幂等键
     * @param outcome   SCHEDULED=已编排 / PREEMPTED_OTHERS=抢占成功
     * @param entryId   新编排条目ID
     * @param preempted 本次抢占挤占的条目及重排建议
     */
    public record SubmitAccepted(String requestId, String outcome, long entryId,
                                 List<PreemptedInfo> preempted) {
    }

    /**
     * 冲突明细。
     *
     * @param entryId     已编排条目ID
     * @param requestId   已编排条目来源申请
     * @param windowCode  冲突窗口编码
     * @param startUtc    冲突窗口开始（UTC，含）
     * @param endUtc      冲突窗口结束（UTC，不含）
     * @param priority    已编排条目优先级
     */
    public record ConflictInfo(long entryId, String requestId, String windowCode,
                               String startUtc, String endUtc, int priority) {
    }

    /**
     * 发布请求。
     *
     * @param day 发布日（UTC，yyyy-MM-dd）
     */
    public record PublishRequest(String day) {
    }

    /**
     * 发布结果。
     *
     * @param day              发布日
     * @param version          版本号
     * @param entryCount       本版本新纳入条目数
     * @param idempotentReplay 是否为重复发布的幂等成功
     */
    public record PublishResult(String day, int version, int entryCount, boolean idempotentReplay) {
    }

    /**
     * 编排条目视图。
     */
    public record EntryView(long id, String requestId, String windowCode, String satelliteCode,
                            String stationCode, String startUtc, String endUtc, int priority,
                            String status, Integer publishedVersion, String preemptedByRequestId) {
    }

    /**
     * 发布版本视图。
     */
    public record PublicationView(int version, int entryCount, String publishedAt) {
    }
}
