package com.example.starter.flood.repo;

import java.time.LocalDateTime;

/**
 * 在途指令冲突替代建议 PO：新方案版本不溯及在途指令，冲突时给出替代建议由人工确认，留痕可查。
 */
public class SuggestionPO {
    public Long id;
    public String suggestionNo;
    public Long orderId;
    public Long oldPlanId;
    public Long newPlanId;
    public Integer oldVersionNo;
    public Integer newVersionNo;
    public String suggestionDetail;
    /** PENDING 待确认 / CONFIRMED 已人工确认。 */
    public String status;
    /** 人工确认决策：ADOPT_NEW_VERSION / KEEP_ORIGINAL_VERSION。 */
    public String decision;
    public String confirmedBy;
    public LocalDateTime confirmedAt;
    public String confirmNote;
    public String confirmRequestId;
    public LocalDateTime createdAt;
}
