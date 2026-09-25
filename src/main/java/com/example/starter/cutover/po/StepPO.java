package com.example.starter.cutover.po;

import java.time.LocalDateTime;

/**
 * 割接步骤持久化对象，对应表 cutover_step。
 */
public class StepPO {

    private Long id;
    private Long cutoverId;
    /** 步骤序号，从 1 开始。 */
    private Integer seq;
    private String name;
    /** 步骤类型枚举名：NORMAL/VERIFY。 */
    private String stepType;
    /** 步骤状态枚举名：PENDING/RUNNING/DONE/FAILED/FROZEN。 */
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCutoverId() {
        return cutoverId;
    }

    public void setCutoverId(Long cutoverId) {
        this.cutoverId = cutoverId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStepType() {
        return stepType;
    }

    public void setStepType(String stepType) {
        this.stepType = stepType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
