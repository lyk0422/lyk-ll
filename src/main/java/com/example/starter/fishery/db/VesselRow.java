package com.example.starter.fishery.db;

import java.time.Instant;

/**
 * 渔船台账行（合成数据）。
 */
public record VesselRow(String code, String name, Instant createdAt) {
}
