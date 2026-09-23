package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 一次灰度发布（显式指定车辆集合，批量原子切换）。
 *
 * <p>状态：IN_PROGRESS 灰度中；PROMOTED 已全量转正；ROLLED_BACK 已回滚。
 * 回滚只影响本次灰度车辆，并恢复各自发布前版本。</p>
 */
public class CanaryRelease {

    /** 主键。 */
    private long id;
    /** 灰度批次业务 ID（用于追溯）。 */
    private String releaseId;
    /** 目标参数键。 */
    private String paramKey;
    /** 灰度采用的新版本 ID。 */
    private long canaryVersionId;
    /** 灰度状态。 */
    private CanaryStatus status;
    /** 发起操作者。 */
    private String createdBy;
    /** 发起时间（UTC）。 */
    private Instant createdAt;
    /** 转正操作者；null 表示未转正。 */
    private String promotedBy;
    /** 转正时间（UTC）；null 表示未转正。 */
    private Instant promotedAt;
    /** 回滚操作者；null 表示未回滚。 */
    private String rolledBackBy;
    /** 回滚时间（UTC）；null 表示未回滚。 */
    private Instant rolledBackAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(String releaseId) {
        this.releaseId = releaseId;
    }

    public String getParamKey() {
        return paramKey;
    }

    public void setParamKey(String paramKey) {
        this.paramKey = paramKey;
    }

    public long getCanaryVersionId() {
        return canaryVersionId;
    }

    public void setCanaryVersionId(long canaryVersionId) {
        this.canaryVersionId = canaryVersionId;
    }

    public CanaryStatus getStatus() {
        return status;
    }

    public void setStatus(CanaryStatus status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getPromotedBy() {
        return promotedBy;
    }

    public void setPromotedBy(String promotedBy) {
        this.promotedBy = promotedBy;
    }

    public Instant getPromotedAt() {
        return promotedAt;
    }

    public void setPromotedAt(Instant promotedAt) {
        this.promotedAt = promotedAt;
    }

    public String getRolledBackBy() {
        return rolledBackBy;
    }

    public void setRolledBackBy(String rolledBackBy) {
        this.rolledBackBy = rolledBackBy;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public void setRolledBackAt(Instant rolledBackAt) {
        this.rolledBackAt = rolledBackAt;
    }
}
