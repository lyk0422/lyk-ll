package com.example.starter.flood.repo;

import java.time.LocalDateTime;

/**
 * 在途指令与新方案版本冲突的替代建议 PO；人工确认留痕。
 */
public class AdvicePO {
    public Long id;
    public String adviceNo;
    /** 冲突的在途指令主键。 */
    public Long orderId;
    /** 新追加的方案版本主键。 */
    public Long newPlanId;
    /** 替代建议内容 JSON。 */
    public String suggestion;
    /** PENDING 待确认 / CONFIRMED 已确认（终态）。 */
    public String status;
    /** 建议生成来源（滚动演算 requestId）。 */
    public String requestId;
    /** 人工决策：ACCEPT_NEW_VERSION / KEEP_CURRENT。 */
    public String decision;
    public String confirmNote;
    public String confirmedBy;
    public LocalDateTime createdAt;
    public LocalDateTime confirmedAt;
}
