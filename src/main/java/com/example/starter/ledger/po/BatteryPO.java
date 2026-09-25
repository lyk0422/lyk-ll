package com.example.starter.ledger.po;

import java.time.Instant;

/**
 * 电池资产主表 PO。时间字段统一为 UTC。
 */
public class BatteryPO {

    /** 主键。 */
    private Long id;
    /** 电池唯一编码。 */
    private String code;
    /** 资产状态，取值见 {@link com.example.starter.ledger.domain.BatteryStatus}。 */
    private String status;
    /** 当前所在站点编码；空值表示不在站（在用/在途/核销）。 */
    private String currentStation;
    /** 绑定车辆编码；空值表示未绑定车辆。 */
    private String boundVehicle;
    /** 事件链最新事件哈希；空值表示尚无事件。 */
    private String headEventHash;
    /** 乐观锁版本号。 */
    private long version;
    /** 建档时间（UTC）。 */
    private Instant createdAt;
    /** 最近更新时间（UTC）。 */
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentStation() {
        return currentStation;
    }

    public void setCurrentStation(String currentStation) {
        this.currentStation = currentStation;
    }

    public String getBoundVehicle() {
        return boundVehicle;
    }

    public void setBoundVehicle(String boundVehicle) {
        this.boundVehicle = boundVehicle;
    }

    public String getHeadEventHash() {
        return headEventHash;
    }

    public void setHeadEventHash(String headEventHash) {
        this.headEventHash = headEventHash;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
