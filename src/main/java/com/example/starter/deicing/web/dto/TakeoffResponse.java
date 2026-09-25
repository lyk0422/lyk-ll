package com.example.starter.deicing.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 起飞回报结果。
 *
 * @param valid true=HOT 有效（已起飞终态）；false=HOT 超时失效，需重新除冰
 * @param replay 是否为终态幂等重放
 */
public record TakeoffResponse(
        QueueItemView item,
        boolean valid,
        boolean replay,
        Instant hotDeadline) {

    public static List<String> noBays() {
        return List.of();
    }
}
