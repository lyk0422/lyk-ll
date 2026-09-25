package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 测区人员定位（合成数据）。onSite=1 表示预警触发时仍在测区范围内、必须撤离。
 */
public class ZonePerson {

    private Long id;
    private String zoneCode;
    private String personId;
    private boolean onSite;
    private LocalDateTime locatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public boolean isOnSite() {
        return onSite;
    }

    public void setOnSite(boolean onSite) {
        this.onSite = onSite;
    }

    public LocalDateTime getLocatedAt() {
        return locatedAt;
    }

    public void setLocatedAt(LocalDateTime locatedAt) {
        this.locatedAt = locatedAt;
    }
}
