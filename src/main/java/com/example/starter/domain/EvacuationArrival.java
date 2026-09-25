package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 撤人到位记录（处置链触发时在册人员快照，一人一行）。
 * arrivedAt 为 NULL 表示尚未到位；撤人确认记录据此可复算。
 */
public class EvacuationArrival {

    private Long id;
    private Long chainId;
    private String zoneCode;
    private String personId;
    private LocalDateTime arrivedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChainId() {
        return chainId;
    }

    public void setChainId(Long chainId) {
        this.chainId = chainId;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public String getPersonId() {
        return personId;
    }

    public void setPersonId(String personId) {
        this.personId = personId;
    }

    public LocalDateTime getArrivedAt() {
        return arrivedAt;
    }

    public void setArrivedAt(LocalDateTime arrivedAt) {
        this.arrivedAt = arrivedAt;
    }
}
