package com.example.starter.water.domain;

import java.util.Optional;

/**
 * 计量点仓储。
 */
public interface MeterRepository {

    /**
     * 计量点行。
     */
    record MeterRow(Long id, Long userId, String name) {
    }

    MeterRow create(Long userId, String name);

    Optional<MeterRow> findById(Long id);
}
