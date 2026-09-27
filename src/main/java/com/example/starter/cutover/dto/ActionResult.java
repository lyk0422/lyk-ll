package com.example.starter.cutover.dto;

/**
 * 写操作统一返回。replayed=true 表示命中幂等，返回首次结果快照，未产生新记录。
 * waiting=true 表示批次执行门禁等待（前置未封板），未产生任何状态变更。
 */
public record ActionResult(
        boolean replayed,
        String message,
        RequestView request,
        Boolean waiting
) {
    public ActionResult(boolean replayed, String message, RequestView request) {
        this(replayed, message, request, null);
    }

    /** 门禁等待结果：状态不变、不写步骤、不占幂等键。 */
    public static ActionResult waiting(String message, RequestView request) {
        return new ActionResult(false, message, request, true);
    }
}
