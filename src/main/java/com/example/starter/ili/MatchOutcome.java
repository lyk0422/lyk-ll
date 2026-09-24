package com.example.starter.ili;

/**
 * 缺陷读数的对齐去向。
 */
public enum MatchOutcome {
    /** 已与另一回合读数配对。 */
    MATCHED,
    /** 仅出现在新回合：新发现缺陷。 */
    NEW_FOUND,
    /** 仅出现在旧回合：已消失缺陷。 */
    DISAPPEARED
}
