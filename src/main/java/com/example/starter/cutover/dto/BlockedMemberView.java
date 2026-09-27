package com.example.starter.cutover.dto;

import java.util.List;

/**
 * 阻断明细视图。
 *
 * @param blockedBy       首个阻断来源根申请编号（传播时冻结，后续波次不覆盖）
 * @param blockingSources 当前处于回退/超期/阻断状态的直接前置集合，按申请编号升序
 */
public record BlockedMemberView(
        String requestId,
        String blockedBy,
        List<String> blockingSources
) {
}
