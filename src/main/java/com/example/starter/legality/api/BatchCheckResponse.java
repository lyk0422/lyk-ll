package com.example.starter.legality.api;

import java.util.List;

/**
 * 整组校验结果。成员状态与整组结论一致：任一成员违规则 {@code legal=false}。
 *
 * @param batchId       批次ID
 * @param legal         整组判定
 * @param status        批次状态：CHECKED / FINALIZED
 * @param memberResults 各成员独立判定结果
 */
public record BatchCheckResponse(
        String batchId,
        boolean legal,
        String status,
        List<MemberResult> memberResults) {

    /**
     * 单个成员的聚合判定结果（成员在批次内可有多段值勤）。
     *
     * @param crewId  机组成员ID
     * @param legal   成员级判定：全部段合法才为 true
     * @param reasons 各段值勤违规原因码并集
     */
    public record MemberResult(
            String crewId,
            boolean legal,
            List<String> reasons) {
    }
}
