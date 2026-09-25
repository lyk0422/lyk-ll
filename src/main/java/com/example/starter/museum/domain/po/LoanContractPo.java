package com.example.starter.museum.domain.po;

import com.example.starter.museum.domain.ContractStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 借展合同行。
 * endDate：当前届满日（UTC 日界），续展后后移，最长不超过 startDate+3 年。
 * originalDays：原始期限天数，续展增量不得超过其一半。
 * version：乐观锁版本号，用于归还点交/续展并发形成唯一终态。
 */
public class LoanContractPo {
    public Long id;
    public String contractNo;
    public Long artifactId;
    public String borrower;
    public LocalDate startDate;
    public LocalDate endDate;
    public Integer originalDays;
    public ContractStatus status;
    public Long showcaseId;
    public Integer renewCount;
    public Instant suspendedAt;
    public Instant resumedAt;
    public Instant returnedAt;
    public Instant closedAt;
    public Integer version;
    public Instant createdAt;
}
