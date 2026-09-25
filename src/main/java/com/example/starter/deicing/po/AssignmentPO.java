package com.example.starter.deicing.po;

import com.example.starter.deicing.model.AssignmentState;

/**
 * 资源占用记录（deicing_assignment 表一行）：每辆车在一个车位上服务一个机位的半开区间。
 */
public class AssignmentPO {

    /** 自增主键。 */
    private long id;
    /** 对应申请号。 */
    private String requestId;
    /** 除冰轮次。 */
    private int roundNo;
    /** 车位编号。 */
    private String bayId;
    /** 除冰车编号。 */
    private String truckId;
    /** 占用起点（含）。 */
    private long startTs;
    /** 占用终点（不含）。 */
    private long endTs;
    /** 作业实际结束时刻；未结束为 null。 */
    private Long actualEndTs;
    /** 占用状态。 */
    private AssignmentState state;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public int getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(int roundNo) {
        this.roundNo = roundNo;
    }

    public String getBayId() {
        return bayId;
    }

    public void setBayId(String bayId) {
        this.bayId = bayId;
    }

    public String getTruckId() {
        return truckId;
    }

    public void setTruckId(String truckId) {
        this.truckId = truckId;
    }

    public long getStartTs() {
        return startTs;
    }

    public void setStartTs(long startTs) {
        this.startTs = startTs;
    }

    public long getEndTs() {
        return endTs;
    }

    public void setEndTs(long endTs) {
        this.endTs = endTs;
    }

    public Long getActualEndTs() {
        return actualEndTs;
    }

    public void setActualEndTs(Long actualEndTs) {
        this.actualEndTs = actualEndTs;
    }

    public AssignmentState getState() {
        return state;
    }

    public void setState(AssignmentState state) {
        this.state = state;
    }
}
