package com.example.starter.museum.domain.po;

import java.time.Instant;

/** 领域事件行：申请/点交/挂起/恢复/越限/迟到等，供历史与轨迹复算。 */
public class LoanEventPo {
    public Long id;
    public Long contractId;
    public Long showcaseId;
    public String type;
    public String detail;
    public Instant createdAt;
}
