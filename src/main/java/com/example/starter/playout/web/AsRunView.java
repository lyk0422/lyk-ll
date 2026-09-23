package com.example.starter.playout.web;

import java.time.Instant;

/**
 * as-run 播出日志段视图。
 *
 * @param asRunSequence  频道内单调序号
 * @param kind           ITEM-播单素材 / LIVE-直播插播
 * @param itemId         播单节目项ID（kind=ITEM 时非空）
 * @param materialDigest 素材摘要（kind=ITEM 时非空）
 * @param liveRequestId  直播插播 requestId（kind=LIVE 时非空）
 * @param priority       直播优先级（kind=LIVE 时非空）
 * @param start          本段实际播出开始时间(UTC)
 * @param end            本段实际播出结束时间(UTC)，开放段为 null
 * @param ackState       PENDING / ACKED
 */
public record AsRunView(
        long asRunSequence,
        String kind,
        String itemId,
        String materialDigest,
        String liveRequestId,
        Integer priority,
        Instant start,
        Instant end,
        String ackState) {
}
