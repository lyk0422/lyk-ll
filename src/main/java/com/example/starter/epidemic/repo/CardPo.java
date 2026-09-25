package com.example.starter.epidemic.repo;

import java.time.LocalDateTime;

/**
 * epidemic_card 表实体：报告卡主表。
 */
public class CardPo {
    public Long id;
    public String cardNumber;
    public String patientId;
    public String patientName;
    public String diseaseCode;
    public java.time.LocalDate onsetDate;
    /** 去重键：有效卡=患者|病种|发病日期，撤回终态=卡片编号墓碑值。 */
    public String dedupKey;
    public int currentVersion;
    public String status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
