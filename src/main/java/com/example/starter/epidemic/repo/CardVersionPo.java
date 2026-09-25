package com.example.starter.epidemic.repo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * card_version 表实体：卡片版本链。所有时间列为 UTC。
 */
public class CardVersionPo {
    public Long id;
    public String cardNumber;
    public int versionNo;
    public String diseaseCode;
    public String diagnosisType;
    public LocalDate onsetDate;
    public LocalDateTime diagnosisTime;
    public LocalDateTime submittedAt;
    public LocalDateTime deadline;
    public boolean timely;
    public String lateReason;
    public String facilityCode;
    public String reporter;
    public String changeKind;
    public boolean diseaseChanged;
    public String notes;
    public LocalDateTime createdAt;
}
