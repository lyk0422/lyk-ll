package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.Conclusion;
import com.example.starter.epidemic.domain.InvestigationStatus;

import java.time.Instant;
import java.util.List;

/**
 * 协查单详情。
 *
 * @param id          协查单号
 * @param eventNo     所属聚集事件编号
 * @param requestId   幂等键
 * @param status      状态 OPEN/CONCLUDED
 * @param conclusion  协查结论，未结案为 null
 * @param note        协查说明
 * @param creator     发起人（自动生成为 system）
 * @param operator    结案操作人，未结案为 null
 * @param targets     被推送机构编码（相关机构）
 * @param createdAt   生成时刻（UTC）
 * @param concludedAt 结案时刻（UTC），未结案为 null
 */
public record InvestigationResponse(
        long id,
        String eventNo,
        String requestId,
        InvestigationStatus status,
        Conclusion conclusion,
        String note,
        String creator,
        String operator,
        List<String> targets,
        Instant createdAt,
        Instant concludedAt
) {
}
