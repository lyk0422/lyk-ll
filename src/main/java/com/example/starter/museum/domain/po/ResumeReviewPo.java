package com.example.starter.museum.domain.po;

import java.time.Instant;

/** 挂起恢复复核行：环境恢复后须借出借入双方复核，单方不生效。 */
public class ResumeReviewPo {
    public Long id;
    public Long contractId;
    public Long alertId;
    public Boolean lenderConfirmed;
    public Boolean borrowerConfirmed;
    public Instant completedAt;
    public Instant createdAt;
    public Instant updatedAt;
}
