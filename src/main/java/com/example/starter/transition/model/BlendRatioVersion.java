package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回抄浆掺配比例版本：与改品种单号绑定留档，版本链可复算。
 */
public class BlendRatioVersion {

    private Long id;
    private String orderNo;
    /** 比例版本号，单内自增从 1 开始。 */
    private Integer versionNo;
    /** 目标回抄浆掺配比例，单位 %，须在合成允许区间内（含边界）。 */
    private BigDecimal targetRatioPct;
    /** 来源：APPLY=申请携带，ADJUST=执行中调整。 */
    private String source;
    private String requestId;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public BigDecimal getTargetRatioPct() {
        return targetRatioPct;
    }

    public void setTargetRatioPct(BigDecimal targetRatioPct) {
        this.targetRatioPct = targetRatioPct;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
