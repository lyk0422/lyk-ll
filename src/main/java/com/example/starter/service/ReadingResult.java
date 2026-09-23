package com.example.starter.service;

import com.example.starter.domain.TemperatureReading;

/**
 * 写读数结果。
 *
 * @param reading  首次或已存的读数
 * @param replayed 是否为同序号同参重放（未产生新写入）
 */
public record ReadingResult(TemperatureReading reading, boolean replayed) {
}
