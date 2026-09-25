package com.example.starter.quarantine.domain;

import java.time.Instant;

/**
 * 养殖场可售存栏批次行（耳标段）。
 */
public class StockPo {

    private long id;
    private String farmId;
    private AnimalType animalType;
    private String earTagRange;
    /** 初始可售存栏，单位：头/只。 */
    private int totalCount;
    /** 当前可售存栏，单位：头/只；出证核减，作废返还。 */
    private int availableCount;
    private int version;
    private Instant createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getFarmId() {
        return farmId;
    }

    public void setFarmId(String farmId) {
        this.farmId = farmId;
    }

    public AnimalType getAnimalType() {
        return animalType;
    }

    public void setAnimalType(AnimalType animalType) {
        this.animalType = animalType;
    }

    public String getEarTagRange() {
        return earTagRange;
    }

    public void setEarTagRange(String earTagRange) {
        this.earTagRange = earTagRange;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getAvailableCount() {
        return availableCount;
    }

    public void setAvailableCount(int availableCount) {
        this.availableCount = availableCount;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
