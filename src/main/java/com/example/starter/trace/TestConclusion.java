package com.example.starter.trace;

/**
 * 检测结论：PASS 快检合格，SUSPECT 快检疑似（抑制率≥50%），
 * OVERTURNED 复检合格推翻快检，CONFIRMED 复检不合格确认疑似。
 */
public enum TestConclusion {
    PASS,
    SUSPECT,
    OVERTURNED,
    CONFIRMED
}
