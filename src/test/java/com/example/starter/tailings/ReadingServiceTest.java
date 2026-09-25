package com.example.starter.tailings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.tailings.domain.ReadingType;
import com.example.starter.tailings.service.ReadingService;
import com.example.starter.tailings.support.IntegrationTestSupport;

/**
 * 监测读数：测点+采样时刻去重、乱序按时刻归位、归档后迟到读数只记事件。
 */
class ReadingServiceTest extends IntegrationTestSupport {

    @Autowired
    private ReadingService readingService;

    @Test
    void dedupByPointAndSampleTime() {
        long pondId = createPond();
        LocalDateTime t = LocalDateTime.of(2026, 9, 25, 8, 0);
        ReadingService.ReadingResult first = readingService.submit(
                new ReadingService.ReadingRequest(pondId, "P1", ReadingType.WATER_LEVEL, t, bd("190.5")));
        assertFalse(first.deduplicated());
        ReadingService.ReadingResult dup = readingService.submit(
                new ReadingService.ReadingRequest(pondId, "P1", ReadingType.WATER_LEVEL, t, bd("190.5")));
        assertTrue(dup.deduplicated());
        assertEquals(first.readingId(), dup.readingId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reading WHERE pond_id = ?",
                Integer.class, pondId));
    }

    @Test
    void outOfOrderReadingsAreSortedBySampleTime() {
        long pondId = createPond();
        readingService.submit(new ReadingService.ReadingRequest(
                pondId, "R1", ReadingType.RAINFALL, LocalDateTime.of(2026, 9, 25, 10, 0), bd("30")));
        readingService.submit(new ReadingService.ReadingRequest(
                pondId, "R1", ReadingType.RAINFALL, LocalDateTime.of(2026, 9, 25, 8, 0), bd("10")));
        readingService.submit(new ReadingService.ReadingRequest(
                pondId, "R1", ReadingType.RAINFALL, LocalDateTime.of(2026, 9, 25, 9, 0), bd("20")));
        List<ReadingService.ReadingView> views = readingService.list(pondId, "R1", ReadingType.RAINFALL);
        assertEquals(3, views.size());
        assertEquals(LocalDateTime.of(2026, 9, 25, 8, 0), views.get(0).sampleTime());
        assertEquals(LocalDateTime.of(2026, 9, 25, 9, 0), views.get(1).sampleTime());
        assertEquals(LocalDateTime.of(2026, 9, 25, 10, 0), views.get(2).sampleTime());
    }

    @Test
    void lateReadingAfterArchiveOnlyRecordsLateEvent() {
        long pondId = createPond();
        runFullChain(pondId);
        reviewService.archive(rid(), pondId);
        ReadingService.ReadingResult late = readingService.submit(new ReadingService.ReadingRequest(
                pondId, "P1", ReadingType.WATER_LEVEL, LocalDateTime.of(2026, 9, 25, 12, 0), bd("191")));
        assertTrue(late.late());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reading WHERE pond_id = ?",
                Integer.class, pondId));
        assertEquals(1, readingService.lateEvents(pondId).size());
    }
}
