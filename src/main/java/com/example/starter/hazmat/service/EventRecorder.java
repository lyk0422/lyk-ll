package com.example.starter.hazmat.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.hazmat.repo.HazmatRepository;

/**
 * 独立事务事件记录：例如逆序打卡被拒绝（业务事务回滚）时，告警事件必须独立提交保留。
 */
@Service
public class EventRecorder {

    private final HazmatRepository repository;

    public EventRecorder(HazmatRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNewTransaction(long shipmentId, String eventType, String detail, LocalDateTime eventTime) {
        repository.insertEvent(shipmentId, eventType, detail, eventTime);
    }
}
