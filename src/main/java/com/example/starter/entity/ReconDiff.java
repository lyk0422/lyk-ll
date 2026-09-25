package com.example.starter.entity;

import java.time.Instant;
import java.time.LocalDate;

/** 对账差异记录及处理留痕。 */
public class ReconDiff {
    private Long id;
    private LocalDate batchDate;
    private String channelFlowNo;
    /** 渠道多账（本地无此笔）时可能为 null。 */
    private String merchantNo;
    /** LOCAL_EXTRA / CHANNEL_EXTRA / AMOUNT_MISMATCH。 */
    private String diffType;
    /** 本地金额（分）；渠道多账时为 null。 */
    private Long localAmount;
    /** 渠道金额（分）；本地多账时为 null。 */
    private Long channelAmount;
    /** PENDING / RESOLVED。 */
    private String status;
    /** WRITE_OFF / SUPPLEMENT / REFUND。 */
    private String resolution;
    private Instant resolvedAt;
    private String resolvedBy;
    private String remark;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getBatchDate() { return batchDate; }
    public void setBatchDate(LocalDate batchDate) { this.batchDate = batchDate; }
    public String getChannelFlowNo() { return channelFlowNo; }
    public void setChannelFlowNo(String channelFlowNo) { this.channelFlowNo = channelFlowNo; }
    public String getMerchantNo() { return merchantNo; }
    public void setMerchantNo(String merchantNo) { this.merchantNo = merchantNo; }
    public String getDiffType() { return diffType; }
    public void setDiffType(String diffType) { this.diffType = diffType; }
    public Long getLocalAmount() { return localAmount; }
    public void setLocalAmount(Long localAmount) { this.localAmount = localAmount; }
    public Long getChannelAmount() { return channelAmount; }
    public void setChannelAmount(Long channelAmount) { this.channelAmount = channelAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
