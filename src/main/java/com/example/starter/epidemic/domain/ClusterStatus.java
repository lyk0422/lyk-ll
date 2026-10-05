package com.example.starter.epidemic.domain;

/**
 * 聚集性疫情事件状态。
 *
 * <p>OPEN 进行中；CONFIRMED/EXCLUDED 为协查结论回写后的状态；CLOSED 为终态，
 * 关闭后重复关闭幂等成功，迟到的报告卡只记迟到事件、可另行归并成新事件。</p>
 */
public enum ClusterStatus {
    /** 进行中：事件生成后尚未回写协查结论。 */
    OPEN,
    /** 协查确认：协查结论为确认聚集。 */
    CONFIRMED,
    /** 协查排除：协查结论为排除聚集。 */
    EXCLUDED,
    /** 已关闭（终态）：不再接受正常归并，迟到卡只记迟到事件。 */
    CLOSED
}
