package com.example.starter.quarantine.api.dto;

import com.example.starter.quarantine.domain.CertificateStatus;
import com.example.starter.quarantine.domain.RequestStatus;

/**
 * 落地报告处理结果。
 *
 * @param accepted         本批是否被接受（超时/超量/证失效/结案后迟到均为 false）
 * @param cumulativeLanded 截至本次被接受的累计落地数量，单位 头/只
 * @param requestStatus    处理后单据状态
 * @param certificateStatus 处理后证状态
 * @param quarantineFlag   强制隔离观察标记（在途封锁联动）
 * @param closed           本次处理后是否结案
 * @param lateEvent        是否为结案后的迟到事件（只记录不改状态）
 * @param note             拒绝/说明原因
 * @param idempotentReplay 是否同键同配重放首次结果
 */
public record LandingResultView(
        String requestId,
        String reportId,
        int quantity,
        boolean accepted,
        int cumulativeLanded,
        RequestStatus requestStatus,
        CertificateStatus certificateStatus,
        boolean quarantineFlag,
        boolean closed,
        boolean lateEvent,
        String note,
        boolean idempotentReplay) {
}
