package com.example.starter.sync.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.sync.SensorType;
import com.example.starter.sync.domain.DiscardReason;
import com.example.starter.sync.domain.IngestOutcome;
import com.example.starter.sync.engine.SyncEngine;
import com.example.starter.sync.repo.EventRepository;
import com.example.starter.sync.repo.MessageRepository;
import com.example.starter.sync.repo.SyncEventRow;

/**
 * 传感器消息接收服务：幂等接收、水位线与迟到窗口判定、丢弃原因落库。
 */
@Service
public class SensorSyncService {

    private final SyncEngine engine;
    private final MessageRepository messageRepository;
    private final EventRepository eventRepository;

    public SensorSyncService(SyncEngine engine, MessageRepository messageRepository,
                             EventRepository eventRepository) {
        this.engine = engine;
        this.messageRepository = messageRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 接收一条传感器消息。重复消息幂等丢弃；迟到或命中已提交帧窗口的消息记录原因后丢弃。
     */
    @Transactional
    public IngestOutcome ingest(SensorType type, String messageId, long rawTs) {
        IngestOutcome outcome = engine.ingest(type, messageId, rawTs);
        if (outcome.isAccepted()) {
            messageRepository.insertAccepted(outcome.sample());
            eventRepository.append(SyncEventRow.MESSAGE_ACCEPTED, type, messageId, null,
                    rawTs, outcome.sample().driftVersion(), null, null, null);
        } else if (outcome.reason() == DiscardReason.DUPLICATE_MESSAGE) {
            eventRepository.append(SyncEventRow.MESSAGE_DUPLICATE, type, messageId, null,
                    rawTs, null, null, null, outcome.reason().name());
        } else {
            messageRepository.insertDiscarded(outcome.sample(), outcome.reason());
            eventRepository.append(SyncEventRow.MESSAGE_DISCARDED, type, messageId, null,
                    rawTs, outcome.sample().driftVersion(), null, null, outcome.reason().name());
        }
        return outcome;
    }
}
