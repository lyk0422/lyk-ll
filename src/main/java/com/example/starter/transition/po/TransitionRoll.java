package com.example.starter.transition.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 过渡纸卷；按卷号唯一登记，登记即强制降级。
 */
public class TransitionRoll {
    public Long id;
    /** 卷号，全库唯一。 */
    public String rollNo;
    public String orderNo;
    public String machineId;
    /** 纸卷长度，米。 */
    public BigDecimal lengthM;
    /** DOWNGRADED/QUALIFIED 终态等级。 */
    public String finalGrade;
    /** PENDING/INSPECTED/LATE 检验状态。 */
    public String inspectionStatus;
    /** 是否达正品门槛（含恰达边界）；未检为 null。 */
    public Boolean gatePassed;
    /** 实测克重 g/m²；未检为 null。 */
    public BigDecimal measuredBasisWeight;
    /** 实测灰分等级；未检为 null。 */
    public Integer measuredAshLevel;
    /** 实测抗张指数 N·m/g；未检为 null。 */
    public BigDecimal measuredTensile;
    /** 已用复议次数（0 或 1）。 */
    public Integer appealCount;
    public LocalDateTime createdAt;
    public LocalDateTime inspectedAt;
    public LocalDateTime appealedAt;
}
