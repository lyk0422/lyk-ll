package com.example.starter.playout.web;

import java.time.Instant;
import java.util.List;

/**
 * 调度 tick 结果：仅包含本次 tick 新生成的播出段；重复 tick 不生成新记录。
 *
 * @param channel   频道标识
 * @param tickTime  本次结算时刻(UTC)
 * @param watermark 结算后水位线(UTC)
 * @param segments  本次新生成的播出段（按 asRunSequence 升序）
 */
public record TickResponse(
        String channel,
        Instant tickTime,
        Instant watermark,
        List<AsRunView> segments) {
}
