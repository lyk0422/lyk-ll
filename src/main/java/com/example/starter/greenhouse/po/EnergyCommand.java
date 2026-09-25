package com.example.starter.greenhouse.po;

import com.example.starter.greenhouse.domain.CommandType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 环控指令与能耗入账记录（kWh）。通风类 energyKwh 恒为 0。
 * request_id 唯一约束承载指令级重放去重。
 */
public class EnergyCommand {
    private Long id;
    private Long zoneId;
    private LocalDate budgetDay;
    private CommandType commandType;
    private BigDecimal energyKwh;
    private String requestId;
    private Instant issuedAt;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public LocalDate getBudgetDay() { return budgetDay; }
    public void setBudgetDay(LocalDate v) { this.budgetDay = v; }
    public CommandType getCommandType() { return commandType; }
    public void setCommandType(CommandType v) { this.commandType = v; }
    public BigDecimal getEnergyKwh() { return energyKwh; }
    public void setEnergyKwh(BigDecimal v) { this.energyKwh = v; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant v) { this.issuedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
