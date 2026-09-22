package com.example.starter.persistence;

import com.example.starter.domain.AssociationEvidence;
import com.example.starter.domain.TrackEvent;

import java.util.List;
import java.util.Optional;

/**
 * 事件流、证据与检测去重数据访问（骨架，实现随后补全）。
 */
public interface EventStore {

    void appendEvent(TrackEvent event);

    void appendEvidence(AssociationEvidence evidence);

    List<TrackEvent> loadEventsUpTo(Long asOfInclusive);

    List<AssociationEvidence> findEvidence(String trackUid);

    Optional<ProcessedDetectionRecord> findProcessed(String detectionId);

    /**
     * 登记检测处理结果；重复 detectionId 插入时抛出
     * {@link org.springframework.dao.DuplicateKeyException}。
     */
    void insertProcessed(ProcessedDetectionRecord record);

    /** 已处理检测记录。 */
    record ProcessedDetectionRecord(
            String detectionId,
            String trackUid,
            String source,
            long observedAt,
            String category,
            double confidence,
            String result,
            String rejectReason,
            long createdAt) {
    }
}
