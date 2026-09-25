package com.example.starter.transition.po;

import java.time.LocalDateTime;

/**
 * 过渡卷判定链明细：首次强制降级与一次复议改判均保留可查，支持复算。
 */
public class RollJudgement {
    public Long id;
    public String rollNo;
    /** 轮次：1 首次判定，2 复议改判。 */
    public Integer roundNo;
    /** DOWNGRADED/QUALIFIED。 */
    public String result;
    /** 本轮检验是否达门槛；复议沿用上轮时可能为 null 之外的沿用值。 */
    public Boolean gatePassed;
    public String reason;
    /** 触发本轮判定的幂等 requestId。 */
    public String requestId;
    public LocalDateTime createdAt;
}
