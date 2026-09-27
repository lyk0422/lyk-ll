package com.example.starter.cutover.dto;

import java.util.List;

/**
 * 写操作统一返回。
 *
 * @param replayed true 表示命中幂等，返回首次结果快照，未产生新记录
 * @param waiting  true 表示批次执行门禁未满足（直接前置尚未全部验证封板）；
 *                 此时不写步骤、不占用 requestUid，调用方应以后续重试
 * @param waitingOn 门禁等待中的直接前置申请编号（按编号排序）；非等待时为空集合
 */
public record ActionResult(
        boolean replayed,
        String message,
        RequestView request,
        boolean waiting,
        List<String> waitingOn
) {
    public ActionResult(boolean replayed, String message, RequestView request) {
        this(replayed, message, request, false, List.of());
    }
}
