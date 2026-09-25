package com.example.starter.slot.service;

import com.example.starter.slot.dao.HistoricalPerformanceDao;
import com.example.starter.slot.dao.SlotDao;
import com.example.starter.slot.domain.AllocationBasis;
import com.example.starter.slot.domain.SeriesStatus;
import com.example.starter.slot.dto.AllocationView;
import com.example.starter.slot.dto.ConflictView;
import com.example.starter.slot.dto.PublishRequest;
import com.example.starter.slot.dto.PublishResult;
import com.example.starter.slot.dto.ReleaseRequest;
import com.example.starter.slot.dto.ReleaseResult;
import com.example.starter.slot.dto.SlotApplicationRequest;
import com.example.starter.slot.dto.SlotApplicationResult;
import com.example.starter.slot.exception.FingerprintMismatchException;
import com.example.starter.slot.exception.SlotConflictException;
import com.example.starter.slot.exception.SlotNotFoundException;
import com.example.starter.slot.exception.SlotStateException;
import com.example.starter.slot.exception.SlotValidationException;
import com.example.starter.slot.po.HistoricalPerformancePo;
import com.example.starter.slot.po.SeasonPo;
import com.example.starter.slot.po.SlotAllocationPo;
import com.example.starter.slot.po.SlotSeriesPo;
import com.example.starter.slot.po.SnapshotAllocationPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 航班时刻池分配与历史优先权裁决服务。
 *
 * <p>所有写操作以航季行锁（SELECT ... FOR UPDATE）串行化，配合存活占用唯一索引，
 * 保证并发申请同一时刻只成功一家、退回与新申请原子；系列多周占用与系列元数据同事务，
 * 冲突即整体回滚。</p>
 */
@Service
public class SlotCoordinationService {

    /** 历史保留执行率阈值（%）：恰好 80.0 视为达标保留，79.9 及以下进入公共池。 */
    static final double RETENTION_THRESHOLD = 80.0;

    private static final Pattern SEASON_PATTERN = Pattern.compile("^([A-Z]+)(\\d{2})$");

    private static final Pattern SLOT_TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final SlotDao slotDao;

    private final HistoricalPerformanceDao historicalPerformanceDao;

    private final Clock clock;

    public SlotCoordinationService(SlotDao slotDao,
                                   HistoricalPerformanceDao historicalPerformanceDao,
                                   Clock clock) {
        this.slotDao = slotDao;
        this.historicalPerformanceDao = historicalPerformanceDao;
        this.clock = clock;
    }

    /**
     * 系列申请裁决：同键同参幂等返回首次结果，同键异参 409；整周冲突整体回滚。
     */
    @Transactional
    public SlotApplicationResult apply(SlotApplicationRequest request) {
        List<Integer> weeks = normalizeWeeks(request.weeks());
        String slotTime = normalizeSlotTime(request.slotTime());
        String fingerprint = fingerprint(request.requestId(), request.flightNo(), slotTime, weeks);

        slotDao.insertSeasonIfAbsent(request.season());
        SeasonPo season = lockSeason(request.season());
        if (season.frozen() == 1) {
            throw new SlotStateException("season already published and frozen: " + request.season());
        }

        SlotSeriesPo existing = slotDao.findSeriesByRequestId(request.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new FingerprintMismatchException(request.requestId());
            }
            return replayResult(existing);
        }

        String decision = decideRetention(request, slotTime);

        List<SlotAllocationPo> conflicts =
                slotDao.findActiveAllocations(request.season(), request.airport(), slotTime, weeks);
        if (!conflicts.isEmpty()) {
            throw new SlotConflictException(toConflictViews(conflicts));
        }

        SlotSeriesPo series = new SlotSeriesPo(
                null,
                request.requestId(),
                request.season(),
                request.airline(),
                request.flightNo(),
                request.airport(),
                slotTime,
                weeksCsv(weeks),
                fingerprint,
                decision,
                SeriesStatus.HELD.name(),
                0L,
                clock.millis());
        try {
            slotDao.insertSeries(series);
            for (Integer week : weeks) {
                slotDao.insertAllocation(new SlotAllocationPo(
                        null,
                        request.season(),
                        request.airport(),
                        slotTime,
                        week,
                        request.airline(),
                        request.flightNo(),
                        request.requestId(),
                        decision,
                        1));
            }
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：理论上行锁已串行化，此处将并发兜底冲突转为明确冲突清单并回滚
            List<SlotAllocationPo> raced =
                    slotDao.findActiveAllocations(request.season(), request.airport(), slotTime, weeks);
            throw new SlotConflictException(toConflictViews(raced));
        }

        return new SlotApplicationResult(
                request.requestId(),
                decision,
                SeriesStatus.HELD.name(),
                buildAllocationViews(request.airport(), slotTime, weeks, decision),
                false);
    }

    /**
     * 退回系列：全部覆盖周占用原子释放，释放后立即可被其他航司申请；重复退回幂等。
     */
    @Transactional
    public ReleaseResult release(ReleaseRequest request) {
        SlotSeriesPo series = slotDao.findSeriesByRequestId(request.requestId());
        if (series == null) {
            throw new SlotNotFoundException("series not found: " + request.requestId());
        }
        // 锁航季，与并发新申请及发布串行；冻结后允许迟到退回，但不改变已发布快照
        lockSeason(series.season());

        if (SeriesStatus.RELEASED.name().equals(series.status())) {
            return new ReleaseResult(request.requestId(), SeriesStatus.RELEASED.name(), true);
        }

        slotDao.deactivateAllocations(request.requestId());
        int updated = slotDao.markSeriesReleased(request.requestId(), series.version());
        if (updated == 0) {
            // 并发兜底：已被其他事务退回，按幂等重放处理
            return new ReleaseResult(request.requestId(), SeriesStatus.RELEASED.name(), true);
        }
        return new ReleaseResult(request.requestId(), SeriesStatus.RELEASED.name(), false);
    }

    /**
     * 发布航季分配表快照：发布后本期冻结；重复发布幂等返回同一份快照。
     */
    @Transactional
    public PublishResult publish(PublishRequest request) {
        slotDao.insertSeasonIfAbsent(request.season());
        SeasonPo season = lockSeason(request.season());

        if (season.frozen() == 1) {
            Instant publishedAt = season.frozenAt() == null ? Instant.now(clock) : Instant.ofEpochMilli(season.frozenAt());
            return new PublishResult(request.season(), publishedAt, true, snapshotViews(request.season()));
        }

        List<SlotAllocationPo> active = slotDao.findActiveAllocationsBySeason(request.season());
        slotDao.insertSnapshot(request.season(), active);
        Instant publishedAt = Instant.now(clock);
        boolean frozen = slotDao.freezeSeasonIfOpen(request.season(), publishedAt);
        if (!frozen) {
            SeasonPo current = slotDao.findSeason(request.season());
            Instant at = current != null && current.frozenAt() != null
                    ? Instant.ofEpochMilli(current.frozenAt()) : publishedAt;
            return new PublishResult(request.season(), at, true, snapshotViews(request.season()));
        }
        return new PublishResult(request.season(), publishedAt, false, toAllocationViews(active));
    }

    /**
     * 历史优先权裁决：上一航季同航司、同航班号、同机场、同时刻实际执行率 &ge; 80% 保留原时刻。
     */
    private String decideRetention(SlotApplicationRequest request, String slotTime) {
        String prevSeason = previousSeason(request.season());
        Optional<HistoricalPerformancePo> history = historicalPerformanceDao.find(
                prevSeason, request.airline(), request.flightNo(), request.airport(), slotTime);
        return history.filter(h -> h.executionRate() >= RETENTION_THRESHOLD).isPresent()
                ? AllocationBasis.RETAINED.name()
                : AllocationBasis.POOL.name();
    }

    private SlotApplicationResult replayResult(SlotSeriesPo existing) {
        List<AllocationView> allocations;
        if (SeriesStatus.RELEASED.name().equals(existing.status())) {
            allocations = List.of();
        } else {
            allocations = slotDao.findActiveAllocationsByRequestId(existing.requestId())
                    .stream()
                    .map(a -> new AllocationView(a.airport(), a.slotTime(), a.week(), a.basis()))
                    .toList();
        }
        return new SlotApplicationResult(
                existing.requestId(),
                existing.decision(),
                existing.status(),
                allocations,
                true);
    }

    private SeasonPo lockSeason(String season) {
        SeasonPo locked = slotDao.findSeasonForUpdate(season);
        if (locked == null) {
            throw new SlotStateException("season not initialized: " + season);
        }
        return locked;
    }

    private static List<AllocationView> buildAllocationViews(String airport, String slotTime,
                                                             List<Integer> weeks, String basis) {
        List<AllocationView> views = new ArrayList<>(weeks.size());
        for (Integer week : weeks) {
            views.add(new AllocationView(airport, slotTime, week, basis));
        }
        return views;
    }

    private static List<AllocationView> toAllocationViews(List<SlotAllocationPo> allocations) {
        return allocations.stream()
                .map(a -> new AllocationView(a.airport(), a.slotTime(), a.week(), a.basis()))
                .toList();
    }

    private List<AllocationView> snapshotViews(String season) {
        List<SnapshotAllocationPo> snapshot = slotDao.findSnapshot(season);
        return snapshot.stream()
                .map(s -> new AllocationView(s.airport(), s.slotTime(), s.week(), s.basis()))
                .toList();
    }

    private static List<ConflictView> toConflictViews(List<SlotAllocationPo> allocations) {
        return allocations.stream()
                .map(a -> new ConflictView(
                        a.airport(), a.slotTime(), a.week(), a.airline(), a.flightNo(), a.requestId()))
                .toList();
    }

    /**
     * 覆盖周规范化：去空、去重、升序；不允许非正周序号。
     */
    static List<Integer> normalizeWeeks(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new SlotValidationException("weeks must not be empty");
        }
        List<Integer> sorted = raw.stream()
                .peek(w -> {
                    if (w == null || w <= 0) {
                        throw new SlotValidationException("week must be positive");
                    }
                })
                .distinct()
                .sorted()
                .toList();
        return sorted;
    }

    /**
     * 时刻规范化：严格 HH:mm（两位小时/分钟）且必须为 5 分钟粒度。
     */
    static String normalizeSlotTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SlotValidationException("slotTime must not be blank");
        }
        String value = raw.trim();
        Matcher matcher = SLOT_TIME_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new SlotValidationException("slotTime must use HH:mm format: " + raw);
        }
        LocalTime time = LocalTime.parse(value, TIME_FORMATTER);
        if (time.getMinute() % 5 != 0) {
            throw new SlotValidationException("slotTime must align to 5-minute grid: " + raw);
        }
        return value;
    }

    static String weeksCsv(List<Integer> weeks) {
        return weeks.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * 申请指纹：requestId、航班号、时刻与全部覆盖周规范化表示的组合。
     */
    static String fingerprint(String requestId, String flightNo, String slotTime, List<Integer> weeks) {
        return String.join("|", requestId, flightNo, slotTime, weeksCsv(weeks));
    }

    /**
     * 上一航季推导：S26 -> S25、W26 -> W25；无法识别时追加 -P 后缀。
     */
    static String previousSeason(String season) {
        Matcher matcher = SEASON_PATTERN.matcher(season);
        if (matcher.matches()) {
            int prevYear = (Integer.parseInt(matcher.group(2)) + 99) % 100;
            return matcher.group(1) + String.format("%02d", prevYear);
        }
        return season + "-P";
    }
}
