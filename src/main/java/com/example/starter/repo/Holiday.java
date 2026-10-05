package com.example.starter.repo;

import java.time.LocalDate;

/**
 * 合成节假日日历行。holidayDate 为节假日当日（UTC 日界），节前 3 日触发峰值预测。
 */
public record Holiday(LocalDate holidayDate, String name) {
}
