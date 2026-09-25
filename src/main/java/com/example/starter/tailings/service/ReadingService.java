package com.example.starter.tailings.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.tailings.domain.ReadingType;
import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.repo.ArchiveRepository;
import com.example.starter.tailings.repo.PondRepository;
import com.example.starter.tailings.repo.ReadingRepository;

/**
 * 监测读数：按 测点+采样时刻 去重，乱序按时刻归位；
 * 复盘归档（终态）后到达的迟到读数只记迟到事件。
 */
@Service
public class ReadingService {

    private final ReadingRepository readingRepository;
    private final PondRepository pondRepository;
    private final ArchiveRepository archiveRepository;

    public ReadingService(ReadingRepository readingRepository, PondRepository pondRepository,
                          ArchiveRepository archiveRepository) {
        this.readingRepository = readingRepository;
        this.pondRepository = pondRepository;
        this.archiveRepository = archiveRepository;
    }

    /**
     * 读数入参。
     */
    public record ReadingRequest(long pondId, String pointCode, ReadingType readingType,
                                 LocalDateTime sampleTime, BigDecimal value) {
    }

    /**
     * 读数提交结果。deduplicated=true 表示命中去重返回既有记录；
     * late=true 表示归档后到达，仅记迟到事件。
     */
    public record ReadingResult(Long readingId, boolean deduplicated, boolean late, String message) {
    }

    /**
     * 读数视图（按采样时刻升序）。
     */
    public record ReadingView(long id, String pointCode, String readingType,
                              LocalDateTime sampleTime, BigDecimal value) {
    }

    @Transactional
    public ReadingResult submit(ReadingRequest request) {
        pondRepository.findPond(request.pondId())
                .orElseThrow(() -> BusinessException.notFound("尾矿库不存在: " + request.pondId()));
        if (archiveRepository.findByPond(request.pondId()).isPresent()) {
            long eventId = readingRepository.insertLateEvent(request.pondId(), request.pointCode(),
                    request.readingType().name(), request.sampleTime(), request.value(),
                    "复盘归档后到达的迟到读数，仅记事件不入读数表");
            return new ReadingResult(null, false, true, "已归档，迟到读数仅记迟到事件: " + eventId);
        }
        try {
            long id = readingRepository.insertReading(request.pondId(), request.pointCode(),
                    request.readingType().name(), request.sampleTime(), request.value());
            return new ReadingResult(id, false, false, "已接收");
        } catch (DuplicateKeyException e) {
            ReadingRepository.ReadingRow existing = readingRepository
                    .findByNaturalKey(request.pondId(), request.pointCode(),
                            request.readingType().name(), request.sampleTime())
                    .orElseThrow(() -> e);
            return new ReadingResult(existing.id(), true, false, "测点+采样时刻重复，返回既有记录");
        }
    }

    /**
     * 按测点查询读数，按采样时刻升序归位。
     */
    @Transactional(readOnly = true)
    public List<ReadingView> list(long pondId, String pointCode, ReadingType readingType) {
        return readingRepository.listByPoint(pondId, pointCode, readingType.name()).stream()
                .map(r -> new ReadingView(r.id(), r.pointCode(), r.readingType(), r.sampleTime(), r.value()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReadingRepository.LateEventRow> lateEvents(long pondId) {
        return readingRepository.listLateEvents(pondId);
    }
}
