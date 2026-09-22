package com.example.starter.sync.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.sync.SensorType;
import com.example.starter.sync.domain.DriftParams;
import com.example.starter.sync.engine.SyncEngine;
import com.example.starter.sync.repo.DriftRepository;
import com.example.starter.sync.repo.EventRepository;
import com.example.starter.sync.repo.SyncEventRow;

/**
 * 时钟漂移校正参数服务：支持热更新，版本单调递增，带生效时刻；
 * 新参数只影响其后未提交的候选消息，不改写已提交帧。
 */
@Service
public class DriftService {

    private final SyncEngine engine;
    private final DriftRepository driftRepository;
    private final EventRepository eventRepository;

    public DriftService(SyncEngine engine, DriftRepository driftRepository,
                        EventRepository eventRepository) {
        this.engine = engine;
        this.driftRepository = driftRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 热更新某传感器的漂移校正参数。
     */
    @Transactional
    public DriftParams update(SensorType type, int version, long offsetMs, long effectiveFrom) {
        DriftParams params = new DriftParams(type, version, offsetMs, effectiveFrom);
        engine.updateDrift(params);
        driftRepository.insert(params);
        eventRepository.append(SyncEventRow.DRIFT_UPDATED, type, null, null,
                null, version, offsetMs, effectiveFrom, null);
        return params;
    }

    /**
     * 当前生效的最大参数版本，无参数时为 0。
     */
    public int currentVersion(SensorType type) {
        return engine.currentDriftVersion(type);
    }
}
