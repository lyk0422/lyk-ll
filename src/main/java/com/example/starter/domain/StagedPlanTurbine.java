package com.example.starter.domain;

/**
 * 分段计划参与机组：冻结当前版本起始功率、最终分摊目标与每分钟升降上限。
 * occupiedFlag='Y' 表示机组被本计划占用（每机组同一时刻至多一份执行中分段计划），
 * 完成/取消/指令作废时置 NULL 释放。
 */
public class StagedPlanTurbine {

    private Long id;
    private Long planId;
    private Long turbineId;
    private int startPowerKw;
    private int targetPowerKw;
    private int rampKwPerMin;
    private int deadlineMinutes;
    private String occupiedFlag;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public Long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(Long turbineId) {
        this.turbineId = turbineId;
    }

    public int getStartPowerKw() {
        return startPowerKw;
    }

    public void setStartPowerKw(int startPowerKw) {
        this.startPowerKw = startPowerKw;
    }

    public int getTargetPowerKw() {
        return targetPowerKw;
    }

    public void setTargetPowerKw(int targetPowerKw) {
        this.targetPowerKw = targetPowerKw;
    }

    public int getRampKwPerMin() {
        return rampKwPerMin;
    }

    public void setRampKwPerMin(int rampKwPerMin) {
        this.rampKwPerMin = rampKwPerMin;
    }

    public int getDeadlineMinutes() {
        return deadlineMinutes;
    }

    public void setDeadlineMinutes(int deadlineMinutes) {
        this.deadlineMinutes = deadlineMinutes;
    }

    public String getOccupiedFlag() {
        return occupiedFlag;
    }

    public void setOccupiedFlag(String occupiedFlag) {
        this.occupiedFlag = occupiedFlag;
    }
}
