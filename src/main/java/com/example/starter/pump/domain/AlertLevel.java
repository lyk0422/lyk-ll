package com.example.starter.pump.domain;

/**
 * 合成气象预警级别。
 *
 * @param code            级别代码 BLUE/YELLOW/ORANGE/RED
 * @param name            中文名
 * @param rankNo          严重度排序，越大越严重
 * @param triggerPumpdown 是否触发预降（橙色及以上为 true）
 */
public record AlertLevel(String code, String name, int rankNo, boolean triggerPumpdown) {
}
