package com.example.starter.epidemic.repo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * special_correction 表实体：归档后特殊订正及其双人确认。
 */
public class SpecialCorrectionPo {
    public Long id;
    public String requestId;
    public String cardNumber;
    public String fingerprint;
    public String diseaseCode;
    public String diagnosisType;
    public LocalDate onsetDate;
    public LocalDateTime diagnosisTime;
    public boolean timely;
    public String lateReason;
    public String notes;
    public String status;
    public String confirmer1;
    public String confirmer2;
    public Integer appliedVersionNo;
    public LocalDateTime createdAt;
    public LocalDateTime appliedAt;
}
