package com.example.starter.rail.model;

/**
 * 单条区段冲突明细。按 sectionId、冲突窗口开始时间排序。
 */
public class ConflictDetail {

    /** 发生冲突的区段。 */
    private String sectionId;
    /** 与之冲突的已占用申请。 */
    private String otherRequestId;
    /** 冲突申请优先级，数值越大越紧急。 */
    private int otherPriority;
    /** 冲突申请状态（APPROVED/ACTIVE 等）。 */
    private RequestStatus otherStatus;
    /** 对方实际占用窗开始（含缓冲），UTC epoch 毫秒。 */
    private long otherBufferedStart;
    /** 对方实际占用窗结束（不含，含缓冲），UTC epoch 毫秒。 */
    private long otherBufferedEnd;
    /** 正长度重叠时长，单位毫秒；端点相接时为 0（不构成冲突）。 */
    private long overlapMs;

    public ConflictDetail() {
    }

    public ConflictDetail(String sectionId, String otherRequestId, int otherPriority,
                          RequestStatus otherStatus, long otherBufferedStart,
                          long otherBufferedEnd, long overlapMs) {
        this.sectionId = sectionId;
        this.otherRequestId = otherRequestId;
        this.otherPriority = otherPriority;
        this.otherStatus = otherStatus;
        this.otherBufferedStart = otherBufferedStart;
        this.otherBufferedEnd = otherBufferedEnd;
        this.overlapMs = overlapMs;
    }

    public String getSectionId() {
        return sectionId;
    }

    public void setSectionId(String sectionId) {
        this.sectionId = sectionId;
    }

    public String getOtherRequestId() {
        return otherRequestId;
    }

    public void setOtherRequestId(String otherRequestId) {
        this.otherRequestId = otherRequestId;
    }

    public int getOtherPriority() {
        return otherPriority;
    }

    public void setOtherPriority(int otherPriority) {
        this.otherPriority = otherPriority;
    }

    public RequestStatus getOtherStatus() {
        return otherStatus;
    }

    public void setOtherStatus(RequestStatus otherStatus) {
        this.otherStatus = otherStatus;
    }

    public long getOtherBufferedStart() {
        return otherBufferedStart;
    }

    public void setOtherBufferedStart(long otherBufferedStart) {
        this.otherBufferedStart = otherBufferedStart;
    }

    public long getOtherBufferedEnd() {
        return otherBufferedEnd;
    }

    public void setOtherBufferedEnd(long otherBufferedEnd) {
        this.otherBufferedEnd = otherBufferedEnd;
    }

    public long getOverlapMs() {
        return overlapMs;
    }

    public void setOverlapMs(long overlapMs) {
        this.overlapMs = overlapMs;
    }
}
