package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回抄浆掺配比例版本：与改品种单号绑定留档，版本链可复算。
 */
public class BlendRatioVersion {

    private Long id;
    private String orderNo;
    /** 比例版本号，单内自增，从 1 开始。 */
    private Integer versionNo;
    /** 目标掺配比例，单位 %，须在合成允许区间 [5.000,30.000] 内（恰在边界合法）。 */
    private BigDecimal targetRatioPct;
    /** 相对上一版本目标比例差值绝对值，单位 %；首版本为 null。 */
    private BigDecimal ratioDeltaPct;
    /** 方式判定结论：INITIAL=申请初始版本，DIRECT_ADJUST=在线直调，SPEED_DOWN_ADJUST=降速调整。 */
    private String adjustMode;
    private String requestId;
    /** 判定落库时间，与比例版本在同一事务内写入。 */
    private LocalDateTime decidedAt;

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

    public BigDecimal getRatioDeltaPct() {
        return ratioDeltaPct;
    }

    public void setRatioDeltaPct(BigDecimal ratioDeltaPct) {
        this.ratioDeltaPct = ratioDeltaPct;
    }

    public String getAdjustMode() {
        return adjustMode;
    }

    public void setAdjustMode(String adjustMode) {
        this.adjustMode = adjustMode;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }
}
