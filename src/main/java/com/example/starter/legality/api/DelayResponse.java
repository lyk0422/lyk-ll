package com.example.starter.legality.api;

import java.util.List;

/**
 * 延误延长处理结果。
 *
 * @param requestId       对应值勤记录幂等ID
 * @param applied         是否已应用并重判；{@code false} 表示批次已封板，仅登记为迟到事件
 * @param dailyTotal      该机组成员当日（UTC）累计延长分钟数
 * @param legal           应用延长后的最新成员判定（仅登记迟到时为封板时的终态判定）
 * @param reasons         最新违规原因码列表
 * @param batchFinalized  所属批次是否已封板
 */
public record DelayResponse(
        String requestId,
        boolean applied,
        int dailyTotal,
        boolean legal,
        List<String> reasons,
        boolean batchFinalized) {
}
