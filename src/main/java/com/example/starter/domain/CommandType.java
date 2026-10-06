package com.example.starter.domain;

/**
 * 处置指令类型：AERATION 增氧，WATER_CHANGE 换水，CLOSURE_ISOLATION 停展隔离。
 */
public enum CommandType {
    AERATION,
    WATER_CHANGE,
    CLOSURE_ISOLATION;

    /**
     * 超时升级后的下一级指令类型；停展隔离已为最高级，升级后仍为停展隔离。
     */
    public CommandType next() {
        return switch (this) {
            case AERATION -> WATER_CHANGE;
            case WATER_CHANGE -> CLOSURE_ISOLATION;
            case CLOSURE_ISOLATION -> CLOSURE_ISOLATION;
        };
    }
}
