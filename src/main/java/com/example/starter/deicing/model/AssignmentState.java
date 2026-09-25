package com.example.starter.deicing.model;

/**
 * 车位/除冰车占用记录状态。
 */
public enum AssignmentState {

    /** 已预留：出队确认后、作业开始前。 */
    RESERVED,

    /** 作业进行中：占用锁定，不可被插队。 */
    ACTIVE,

    /** 作业已完成，区间为历史事实。 */
    DONE,

    /** 未开始即被取消，占用已释放。 */
    RELEASED
}
