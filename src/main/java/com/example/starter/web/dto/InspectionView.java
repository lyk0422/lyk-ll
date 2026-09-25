package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单次检验/检测视图；首检与复检均通过批次详情返回，复检生效时首检 effective=false 仍可查。
 *
 * @param type           FIELD=田间检验（纯度），LAB=室内检测（发芽率）
 * @param round          1=首检，2=复检
 * @param requestId      提交幂等键
 * @param value          指标值（百分数，%）
 * @param verdict        PASS=合格，FAIL=不合格
 * @param effective      是否当前有效结论
 * @param retestable     首检不合格时是否仍可申请复检（动态计算）
 * @param retestDeadline 复检受理截止日（UTC 日界，含当日），无可复检项为空
 * @param inspector      检验/检测员
 * @param inspectedAt    检验/检测时间（UTC）
 */
public record InspectionView(
        String type,
        int round,
        String requestId,
        BigDecimal value,
        String verdict,
        boolean effective,
        boolean retestable,
        Instant retestDeadline,
        String inspector,
        Instant inspectedAt
) {
}
