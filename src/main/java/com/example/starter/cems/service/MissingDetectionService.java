package com.example.starter.cems.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.starter.cems.CemsConstants;
import com.example.starter.cems.repo.EventRepository;
import com.example.starter.cems.repo.NoticeRepository;
import com.example.starter.cems.repo.ReadingRepository;
import com.example.starter.cems.web.dto.NoticeView;

/**
 * 缺失检测与督办复算：连续缺失严格超过 2 小时才督办，恰 2 小时不标记。
 * 期望时段为监测开始后的逐小时整点；某整点无小时读数即视为该小时缺失。
 * 本组件的复算为“删除重建”：标记链可随时依据真实读数复算。
 */
@Component
public class MissingDetectionService {

    private final ReadingRepository readingRepository;
    private final NoticeRepository noticeRepository;
    private final EventRepository eventRepository;
    private final Clock clock;

    public MissingDetectionService(ReadingRepository readingRepository,
                                   NoticeRepository noticeRepository,
                                   EventRepository eventRepository,
                                   Clock cemsClock) {
        this.readingRepository = readingRepository;
        this.noticeRepository = noticeRepository;
        this.eventRepository = eventRepository;
        this.clock = cemsClock;
    }

    /**
     * 依据监测开始时刻与当前时刻之间的实际上报小时读数，复算缺失区间，
     * 仅对“连续缺失小时数严格大于 2”的区间生成督办；新出现的区间同时记录缺失督办事件。
     *
     * @param monitoringStart 排口监测开始时刻（许可登记时刻）
     * @param now             判定基准时刻
     * @return 复算后该排口全部督办（按区间正序）
     */
    public List<NoticeView> recompute(String outfallCode, LocalDateTime monitoringStart,
                                      LocalDateTime now) {
        List<NoticeView> previous = noticeRepository.findAllByCode(outfallCode);
        Set<String> previousGaps = new HashSet<>();
        for (NoticeView n : previous) {
            previousGaps.add(n.gapStart() + "/" + n.gapEnd());
        }
        noticeRepository.deleteByCode(outfallCode);

        Set<LocalDateTime> reportedSet = new HashSet<>(readingRepository.findAllHourly(outfallCode)
                .stream()
                .map(r -> r.periodStart())
                .toList());

        // 以“上一个完整小时”为判定终点：当前小时尚未结束，不视为缺失
        LocalDateTime start = PeriodUtil.floorHour(monitoringStart).plusHours(1);
        LocalDateTime end = PeriodUtil.floorHour(now).minusHours(1);
        List<NoticeView> result = new ArrayList<>();
        if (!end.isBefore(start)) {
            LocalDateTime runStart = null;
            LocalDateTime cursor = start;
            while (!cursor.isAfter(end)) {
                boolean missing = !reportedSet.contains(cursor);
                if (missing) {
                    if (runStart == null) {
                        runStart = cursor;
                    }
                } else if (runStart != null) {
                    addGapIfOverTwoHours(outfallCode, runStart, cursor.minusHours(1),
                            previousGaps, result);
                    runStart = null;
                }
                cursor = cursor.plusHours(1);
            }
            if (runStart != null) {
                addGapIfOverTwoHours(outfallCode, runStart, end, previousGaps, result);
            }
        }
        return result;
    }

    private void addGapIfOverTwoHours(String outfallCode, LocalDateTime gapStart,
                                      LocalDateTime gapEnd, Set<String> previousGaps,
                                      List<NoticeView> result) {
        int hours = (int) Duration.between(gapStart, gapEnd).toHours() + 1;
        // 恰 2 小时不标记，必须严格超过 2 小时
        if (hours <= CemsConstants.MISSING_TOLERANCE_HOURS) {
            return;
        }
        LocalDateTime createdAt = LocalDateTime.now(clock);
        long id = noticeRepository.insert(new NoticeView(
                null, outfallCode, gapStart, gapEnd, hours,
                CemsConstants.NOTICE_OPEN, createdAt));
        NoticeView inserted = noticeRepository.findGap(outfallCode, gapStart, gapEnd);
        if (inserted == null || inserted.id() != id) {
            throw new IllegalStateException("督办写入后回读失败");
        }
        result.add(inserted);
        if (!previousGaps.contains(gapStart + "/" + gapEnd)) {
            eventRepository.insertOutfallEvent(outfallCode, "MISSING",
                    "连续数据缺失 " + hours + " 小时（" + gapStart + " 至 " + gapEnd + "）已督办",
                    createdAt);
        }
    }
}
