package com.example.starter.marpol.model;

import java.time.Instant;

/**
 * 船舶（合成数据）。
 */
public record Ship(Long id, String imo, String name, Instant createdAt) {
}
