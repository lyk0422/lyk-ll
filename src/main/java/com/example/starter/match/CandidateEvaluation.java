package com.example.starter.match;

/**
 * 单个候选路段的评分构成、总分、排序与拒绝原因。
 *
 * @param segmentId        候选路段业务标识
 * @param distanceMeters   匹配点到路段的垂直距离，单位米
 * @param distanceScore    距离得分，范围 [0,1]，距离越小越高
 * @param headingScore     航向得分，范围 [0,1]，输入航向与路段航向越接近越高
 * @param connectivityScore 连通性得分，范围 {0,1}，与对端候选集合拓扑连通为 1
 * @param totalScore       总分（确定性加权），范围 [0,1]
 * @param rank             排序名次，从 1 开始，总分降序、并列按 segmentId 字典序
 * @param selected         是否被确定性规则选中
 * @param rejectionReason  拒绝原因；被选中时为 null
 */
public record CandidateEvaluation(
        String segmentId,
        double distanceMeters,
        double distanceScore,
        double headingScore,
        double connectivityScore,
        double totalScore,
        int rank,
        boolean selected,
        String rejectionReason) {
}
