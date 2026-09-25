package com.example.starter.transition.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 改品种单；申请时快照前后品种克重与灰分等级，执行时记录关键参数与过渡料长度。
 */
public class TransitionOrder {
    public Long id;
    public String orderNo;
    public String machineId;
    public String fromGradeCode;
    public String toGradeCode;
    /** 改前克重快照 g/m²。 */
    public BigDecimal basisWeightFrom;
    /** 改后克重快照 g/m²。 */
    public BigDecimal basisWeightTo;
    public Integer ashFrom;
    public Integer ashTo;
    /** APPLIED/EXECUTING/DISPOSED/COMPLETED/ARCHIVED。 */
    public String status;
    /** DIRECT/STOP_CLEAN，申请时判定；未判定为 null。 */
    public String switchMode;
    /** 是否须冲洗网部：灰分等级差 > 2 时为 true。 */
    public Boolean wireFlush;
    /** 实际切换用时，秒；未执行为 null。 */
    public Integer switchSeconds;
    /** 实际车速 m/min；未执行为 null。 */
    public BigDecimal machineSpeed;
    /** 实际上料浓度 %；未执行为 null。 */
    public BigDecimal consistency;
    /** 过渡料长度（米）= 车速 m/min × 切换秒 / 60；未执行为 null。 */
    public BigDecimal transitionLengthM;
    public LocalDateTime createdAt;
    public LocalDateTime executedAt;
    public LocalDateTime completedAt;
    public LocalDateTime archivedAt;
}
