package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.SpecialStatus;

import java.time.Instant;

/**
 * 特殊订正流程状态。
 *
 * @param specialId         流程编号
 * @param cardNo            卡片编号
 * @param status            WAITING_SECOND/EFFECTIVE
 * @param firstReviewer     第一名审核员（未确认时为 null）
 * @param firstAt           第一名确认时刻（UTC）
 * @param secondReviewer    第二名审核员
 * @param secondAt          第二名确认生效时刻（UTC）
 * @param effectiveVersion  生效后新版本号（未生效为 null）
 * @param reason            疫情核实结论
 */
public record SpecialCorrectionResponse(
        Long specialId,
        String cardNo,
        SpecialStatus status,
        String firstReviewer,
        Instant firstAt,
        String secondReviewer,
        Instant secondAt,
        Integer effectiveVersion,
        String reason
) {
}
