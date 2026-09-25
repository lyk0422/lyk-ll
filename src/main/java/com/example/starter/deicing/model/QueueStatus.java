package com.example.starter.deicing.model;

/**
 * 排队记录状态。非终态状态视为“进行中”，同一航班只允许存在一条进行中记录。
 */
public enum QueueStatus {

    /** 排队中，尚未出队确认。 */
    WAITING,

    /** 已出队确认，车位/除冰车时刻已排定，作业尚未开始。 */
    SCHEDULED,

    /** 除冰作业进行中；该占用不可被插队或改动。 */
    IN_PROGRESS,

    /** 除冰已完成，等待起飞回报。 */
    DEICED,

    /** HOT 风险：预计起飞将超出防冰液保持时间，须按最早可重排时刻重新除冰。 */
    HOT_RISK,

    /** 终态：已起飞。 */
    DEPARTED,

    /** 终态：已取消。 */
    CANCELLED,

    /** 终态：实际起飞时刻已超出 HOT，原除冰失效，须重新申请除冰。 */
    HOT_EXPIRED;

    /**
     * @return 是否终态（起飞、取消、HOT 失效）
     */
    public boolean isTerminal() {
        return this == DEPARTED || this == CANCELLED || this == HOT_EXPIRED;
    }
}
