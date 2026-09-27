package com.example.starter.web.dto;

import java.util.List;

/**
 * 风险绑定/变更受理响应。
 *
 * @param batchNo           屠宰批次号
 * @param version           变更后风险版本（首次绑定为 1，每次成功变更加 1）
 * @param sequenceNo        本次产生或扩充的清单序号
 * @param kind              清单类型：INITIAL/EXPANDED/SUPPLEMENTARY
 * @param level             当前风险等级：NORMAL/TIGHTENED
 * @param ruleVersion       当前风险规则版本
 * @param status            清单状态：OPEN/COMPLETED
 * @param sampledHeadNos    当前清单冻结抽检编号（字典序）
 * @param declaredHeadNos   冻结申报头只集合（字典序）
 * @param samplingCompleted 全部清单是否已完成
 * @param quarantineActive  是否处于专项隔离
 * @param late              是否核销后迟到受理：TRUE 只记迟到告警，不追改冻结出厂证据
 */
public record RiskChecklistResponse(
        String batchNo,
        int version,
        long sequenceNo,
        String kind,
        String level,
        String ruleVersion,
        String status,
        List<String> sampledHeadNos,
        List<String> declaredHeadNos,
        boolean samplingCompleted,
        boolean quarantineActive,
        boolean late) {
}
