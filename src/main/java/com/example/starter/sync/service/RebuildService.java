package com.example.starter.sync.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.sync.SyncErrorCode;
import com.example.starter.sync.SyncException;
import com.example.starter.sync.SyncProperties;
import com.example.starter.sync.domain.DriftParams;
import com.example.starter.sync.domain.FusionFrame;
import com.example.starter.sync.engine.SyncEngine;
import com.example.starter.sync.repo.EventRepository;
import com.example.starter.sync.repo.SyncEventRow;

/**
 * 事件重建服务：在同一 JVM 内从 H2 事件记录按序重放，重建指定时刻的融合帧及输入证据。
 * 重放幂等：重复执行得到相同结果；未提交的目标时刻返回 FRAME_NOT_FOUND。
 */
@Service
public class RebuildService {

    private final SyncProperties properties;
    private final EventRepository eventRepository;

    public RebuildService(SyncProperties properties, EventRepository eventRepository) {
        this.properties = properties;
        this.eventRepository = eventRepository;
    }

    /**
     * 重建指定目标时刻的融合帧。只重放到该帧提交事件为止，保证重建结果与提交时一致。
     */
    @Transactional(readOnly = true)
    public FusionFrame rebuild(long targetTs) {
        List<SyncEventRow> events = eventRepository.findAllOrdered();
        SyncEngine replay = new SyncEngine(properties);
        for (SyncEventRow event : events) {
            switch (event.eventType()) {
                case SyncEventRow.DRIFT_UPDATED -> replay.updateDrift(new DriftParams(
                        event.sensorType(), event.driftVersion(), event.offsetMs(), event.effectiveFrom()));
                case SyncEventRow.MESSAGE_ACCEPTED -> replay.ingest(
                        event.sensorType(), event.messageId(), event.rawTs());
                case SyncEventRow.FRAME_COMMITTED -> {
                    if (event.targetTs() == targetTs) {
                        return replay.computeFrame(targetTs);
                    }
                    replay.markCommitted(event.targetTs());
                }
                default -> {
                    // MESSAGE_DUPLICATE / MESSAGE_DISCARDED 为派生记录，重放时无需处理
                }
            }
        }
        throw new SyncException(SyncErrorCode.FRAME_NOT_FOUND,
                "no committed fusion frame at target " + targetTs + " in event log");
    }
}
