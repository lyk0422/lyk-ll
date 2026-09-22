package com.example.starter.web.dto;

import com.example.starter.domain.AssociationEvidence;
import com.example.starter.domain.DetectionOutcome;
import com.example.starter.domain.TrackSnapshot;

import java.util.List;

/**
 * 关联处理响应。
 *
 * @param outcome        CREATED/ASSOCIATED/REJECTED/DUPLICATE
 * @param trackUid       关联或新建的轨迹（被拒绝时为空）
 * @param version        处理后轨迹版本（重复/拒绝时返回当前版本或空）
 * @param status         处理后轨迹状态
 * @param rejectReason   拒绝原因（LOW_CONFIDENCE/OUT_OF_ORDER/OUT_OF_GATE/ILLEGAL_STATE）
 * @param evidence       本次产生的关联证据
 * @param track          处理后轨迹完整快照
 */
public record AssociateResponse(
        DetectionOutcome outcome,
        String trackUid,
        Long version,
        String status,
        String rejectReason,
        List<AssociationEvidence> evidence,
        TrackSnapshot track) {
}
