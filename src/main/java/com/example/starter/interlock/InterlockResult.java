package com.example.starter.interlock;

/**
 * 闭锁评估结果。
 *
 * @param blocked  是否闭锁拒绝
 * @param ruleCode 命中的规则编码，未命中为空
 * @param detail   命中详情，未命中为空
 */
public record InterlockResult(boolean blocked, String ruleCode, String detail) {

    public static InterlockResult pass() {
        return new InterlockResult(false, null, null);
    }

    public static InterlockResult block(String ruleCode, String detail) {
        return new InterlockResult(true, ruleCode, detail);
    }
}
