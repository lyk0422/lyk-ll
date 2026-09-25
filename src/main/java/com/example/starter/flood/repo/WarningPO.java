package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 处置链 PO。
 */
public class WarningPO {
    public Long id;
    public String warningNo;
    public Long pondId;
    public String title;
    public String rainSummary;
    /** ACTIVE / ARCHIVED。 */
    public String status;
    public String requestId;
    public LocalDateTime openedAt;
    public LocalDateTime archivedAt;
}
