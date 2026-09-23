package com.example.starter.rail.model;

/**
 * 计划快照发布记录。发布后内容不可变，申请后续状态变化不得改写。
 */
public class PlanSnapshot {

    /** 快照唯一标识。 */
    private String snapshotId;
    /** 所属申请。 */
    private String requestId;
    /** 发布时的申请版本。 */
    private int versionAtPublish;
    /** 发布时的申请状态。 */
    private RequestStatus statusAtPublish;
    /** 不可变快照内容（JSON 文本）。 */
    private String content;
    /** 发布时刻，UTC epoch 毫秒。 */
    private long publishedAt;

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public int getVersionAtPublish() {
        return versionAtPublish;
    }

    public void setVersionAtPublish(int versionAtPublish) {
        this.versionAtPublish = versionAtPublish;
    }

    public RequestStatus getStatusAtPublish() {
        return statusAtPublish;
    }

    public void setStatusAtPublish(RequestStatus statusAtPublish) {
        this.statusAtPublish = statusAtPublish;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(long publishedAt) {
        this.publishedAt = publishedAt;
    }
}
