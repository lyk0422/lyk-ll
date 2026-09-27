package com.example.starter.web.dto;

import java.util.List;

/**
 * 风险抽检视图：批次全部抽检清单、逐只结果与隔离原因，均由冻结明细复算。
 *
 * @param batchNo           屠宰批次号
 * @param version           风险版本（从 1 开始，每次成功变更加 1）
 * @param currentSequenceNo 当前清单序号（同一批次只一个当前清单；补充清单序号递增）
 * @param currentLevel      当前风险等级：NORMAL/TIGHTENED，未绑定为 NULL
 * @param currentRuleVersion 当前风险规则版本，未绑定为 NULL
 * @param samplingCompleted 全部清单是否已完成（所有冻结样本均有结果）
 * @param allNegative       是否全部样本阴性（任一阳性即为 false）
 * @param quarantineActive  是否处于专项隔离（任一阳性）
 * @param controlled        是否受控批次（已绑定风险等级）
 * @param checklists        清单列表（按序号升序，含原子扩充后的冻结清单与补充清单）
 * @param reasons           专项隔离原因（阳性样本清单）
 */
public record RiskSummaryView(
        String batchNo,
        Integer version,
        Long currentSequenceNo,
        String currentLevel,
        String currentRuleVersion,
        boolean samplingCompleted,
        boolean allNegative,
        boolean quarantineActive,
        boolean controlled,
        List<ChecklistView> checklists,
        List<QuarantineReasonView> reasons) {

    /**
     * 抽检清单视图（冻结快照）。
     *
     * @param sequenceNo       清单序号，批次内唯一，首份为 1
     * @param kind             清单类型：INITIAL 首份 / EXPANDED 提高等级原子扩充 / SUPPLEMENTARY 已完成后补充
     * @param level            冻结风险等级
     * @param ruleVersion      冻结风险规则版本
     * @param status           清单状态：OPEN 进行中 / COMPLETED 已完成
     * @param declaredHeadNos  冻结申报头只集合（字典序）
     * @param samples          冻结抽检编号与逐只结果（按编号字典序）
     * @param createdAt        清单冻结时间（UTC ISO-8601）
     * @param completedAt      清单完成时间（最后一只结果登记时刻），NULL 表示未完成
     */
    public record ChecklistView(
            long sequenceNo,
            String kind,
            String level,
            String ruleVersion,
            String status,
            List<String> declaredHeadNos,
            List<SampleView> samples,
            String createdAt,
            String completedAt) {
    }

    /**
     * 抽检样本视图。
     *
     * @param headNo      头只编号
     * @param result      抽检结果：NEGATIVE 阴性 / POSITIVE 阳性，NULL 表示尚未提交
     * @param submittedAt 结果提交时间（UTC ISO-8601），NULL 表示尚未提交
     */
    public record SampleView(String headNo, String result, String submittedAt) {
    }

    /**
     * 专项隔离原因视图。
     *
     * @param sequenceNo  阳性样本所属清单序号
     * @param headNo      阳性头只编号
     * @param result      结果取值，固定 POSITIVE
     * @param positiveAt  阳性结果提交时间（UTC ISO-8601）
     * @param level       阳性清单冻结风险等级
     * @param ruleVersion 阳性清单冻结风险规则版本
     */
    public record QuarantineReasonView(
            long sequenceNo,
            String headNo,
            String result,
            String positiveAt,
            String level,
            String ruleVersion) {
    }
}
