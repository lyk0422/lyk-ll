package com.example.starter.epidemic.repo;

import java.time.LocalDateTime;

/**
 * review_record 表实体：审核轨迹。
 */
public class ReviewPo {
    public Long id;
    public String cardNumber;
    public int versionNo;
    public String stage;
    public String reviewer;
    public String opinion;
    public String outcome;
    public boolean voided;
    public LocalDateTime createdAt;
}
