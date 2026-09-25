package com.example.starter.schedule.service;

import com.example.starter.schedule.domain.ContactWindow;
import com.example.starter.schedule.domain.GroundStation;
import com.example.starter.schedule.domain.Satellite;
import com.example.starter.schedule.domain.ScheduleEntry;
import com.example.starter.schedule.domain.ScheduleRequest;
import com.example.starter.schedule.domain.ScheduleVersion;
import com.example.starter.schedule.model.EntryStatus;
import com.example.starter.schedule.model.RequestOutcome;
import com.example.starter.schedule.model.VersionStatus;
import com.example.starter.schedule.repository.MasterDataRepository;
import com.example.starter.schedule.repository.RequestRepository;
import com.example.starter.schedule.repository.ScheduleRepository;
import com.example.starter.schedule.repository.StationDayLockRepository;
import com.example.starter.schedule.web.ApiException;
import com.example.starter.schedule.web.dto.ConflictItemDto;
import com.example.starter.schedule.web.dto.PreemptionItemDto;
import com.example.starter.schedule.web.dto.ScheduleRequestDto;
import com.example.starter.schedule.web.dto.ScheduleResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 测控过境窗口编排核心服务：半开区间冲突检测、优先级抢占、同级先到保留、
 * requestId 幂等、按日版本发布与冻结、撤销。
 *
 * <p>并发控制：同站同日事务通过 {@code advisory_lock_station_day} 串行化，
 * 配合行锁与唯一索引，保证并发同站同窗申请只允许一个成功。</p>
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final MasterDataRepository masterData;
    private final ScheduleRepository schedules;
    private final RequestRepository requests;
    private final StationDayLockRepository stationDayLocks;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ScheduleService(MasterDataRepository masterData,
                           ScheduleRepository schedules,
                           RequestRepository requests,
                           StationDayLockRepository stationDayLocks,
                           Clock clock,
                           ObjectMapper objectMapper) {
        this.masterData = masterData;
        this.schedules = schedules;
        this.requests = requests;
        this.stationDayLocks = stationDayLocks;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 编排申请（幂等）。
     */
    @Transactional
    public ScheduleResponseDto submit(ScheduleRequestDto dto) {
        Instant now = Instant.now(clock);
        Satellite satellite = masterData.findSatelliteByCode(dto.satelliteCode())
                .orElseThrow(() -> ApiException.notFound("卫星不存在: " + dto.satelliteCode()));
        GroundStation station = masterData.findStationByCode(dto.stationCode())
                .orElseThrow(() -> ApiException.notFound("地面站不存在: " + dto.stationCode()));
        ContactWindow window = masterData.findWindowById(dto.windowId())
                .orElseThrow(() -> ApiException.notFound("过境窗口不存在: " + dto.windowId()));
        if (window.satelliteId() != satellite.id() || window.stationId() != station.id()) {
            throw ApiException.unprocessable("WINDOW_MISMATCH",
                    "窗口不属于指定的卫星/地面站");
        }
        if (!window.start().isBefore(window.end())) {
            throw ApiException.unprocessable("INVALID_WINDOW", "窗口开始时刻必须早于结束时刻");
        }

        String fingerprint = Fingerprints.of(dto.requestId(), dto.satelliteCode(),
                dto.stationCode(), dto.windowId(), dto.priority());

        // 1) 幂等：request_id 主键保证并发同键只有一条记录
        try {
            requests.tryInsertProcessing(dto.requestId(), fingerprint,
                    satellite.id(), station.id(), window.id(), dto.priority());
        } catch (DuplicateKeyException dup) {
            return replay(dto, fingerprint);
        }

        // 2) 同站同日串行化
        lockStationDay(station.id(), window.dayKey());

        // 3) 定位/创建目标版本
        ScheduleVersion version = resolveTargetVersion(station.id(), window.dayKey(),
                dto.emergency(), now);

        // 4) 半开区间冲突检测（行锁）
        List<ScheduleEntry> overlaps = schedules.findOverlappingScheduledForUpdate(
                version.getId(), window.start(), window.end());

        if (!overlaps.isEmpty() && overlaps.stream().allMatch(e -> e.getPriority() >= dto.priority())) {
            // 同级先到保留，后到者拒绝（存在更高或相同优先级即拒绝；抢占仅在严格更高时发生）
            return reject(dto, window, overlaps);
        }

        List<ScheduleEntry> blockers = overlaps.stream()
                .filter(e -> e.getPriority() >= dto.priority()).toList();
        if (!blockers.isEmpty()) {
            // 同时存在不可抢占的同级/更高冲突条目，拒绝并返回冲突详情
            return reject(dto, window, blockers);
        }

        // 5) 写入新条目后再挤占所有严格低优先级的冲突条目（需回填抢占方条目 id）
        ScheduleEntry entry = new ScheduleEntry();
        entry.setRequestId(dto.requestId());
        entry.setSatelliteId(satellite.id());
        entry.setStationId(station.id());
        entry.setWindowId(window.id());
        entry.setVersionId(version.getId());
        entry.setPriority(dto.priority());
        entry.setStatus(EntryStatus.SCHEDULED);
        entry.setStartTime(window.start());
        entry.setEndTime(window.end());
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        long entryId = schedules.insertEntry(entry);

        List<PreemptionItemDto> preemptions = new ArrayList<>();
        for (ScheduleEntry victim : overlaps) {
            preemptions.add(preempt(victim, window, entryId, now));
        }

        requests.completeOutcome(dto.requestId(), RequestOutcome.SCHEDULED, entryId, null);
        return new ScheduleResponseDto(dto.requestId(), RequestOutcome.SCHEDULED.name(),
                entryId, dto.priority(), false, List.of(), preemptions);
    }

    /**
     * 同键重放：行锁等待首个处理事务结束；指纹一致返回首次结果；同键异参 409 且不改变编排表。
     */
    private ScheduleResponseDto replay(ScheduleRequestDto dto, String fingerprint) {
        ScheduleRequest existing = requests.findByIdForUpdate(dto.requestId())
                .orElseThrow(() -> ApiException.conflict("REQUEST_PROCESSING",
                        "编排申请正在处理中，请稍后重试"));
        if (!existing.isCompleted()) {
            throw ApiException.conflict("REQUEST_PROCESSING",
                    "编排申请正在处理中，请稍后重试");
        }
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "requestId 已存在但卫星/地面站/窗口/优先级不一致，拒绝覆盖");
        }
        List<ConflictItemDto> conflicts = parseConflicts(existing.getConflictDetail());
        return new ScheduleResponseDto(dto.requestId(), existing.getOutcome().name(),
                existing.getResultEntryId(), existing.getPriority(), true, conflicts, List.of());
    }

    private ScheduleResponseDto reject(ScheduleRequestDto dto, ContactWindow window,
                                       List<ScheduleEntry> overlaps) {
        List<ConflictItemDto> conflicts = overlaps.stream()
                .map(e -> new ConflictItemDto(e.getId(), e.getRequestId(), e.getSatelliteId(),
                        e.getPriority(), e.getStartTime(), e.getEndTime(),
                        e.getPriority() == dto.priority() ? "SAME_PRIORITY_FIRST_COME_FIRST_SERVED"
                                : "HIGHER_PRIORITY"))
                .toList();
        requests.completeOutcome(dto.requestId(), RequestOutcome.REJECTED, null,
                writeConflicts(conflicts));
        log.info("编排申请被拒绝 requestId={} station={} day={} 冲突条目数={}",
                dto.requestId(), window.stationId(), window.dayKey(), conflicts.size());
        return new ScheduleResponseDto(dto.requestId(), RequestOutcome.REJECTED.name(),
                null, dto.priority(), false, conflicts, List.of());
    }

    /**
     * 抢占：被抢占条目转“被挤占”并生成重排建议。
     */
    private PreemptionItemDto preempt(ScheduleEntry victim, ContactWindow incoming,
                                      long preemptedByEntryId, Instant now) {
        ContactWindow suggestion = findSuggestion(victim, incoming, now);
        Long suggestionWindowId = suggestion == null ? null : suggestion.id();
        Instant suggestionStart = suggestion == null ? null : suggestion.start();
        Instant suggestionEnd = suggestion == null ? null : suggestion.end();
        int updated = schedules.markPreempted(victim.getId(), preemptedByEntryId,
                suggestionWindowId, suggestionStart, suggestionEnd, now);
        if (updated == 0) {
            throw new IllegalStateException("抢占失败，条目状态已变化: " + victim.getId());
        }
        log.info("发生抢占 victimEntryId={} 被优先级更高的申请挤占 建议窗口={}",
                victim.getId(), suggestionWindowId);
        return new PreemptionItemDto(victim.getId(), victim.getRequestId(),
                victim.getSatelliteId(), victim.getPriority(),
                suggestionWindowId, suggestionStart, suggestionEnd);
    }

    /**
     * 重排建议：同站同日同卫星、不早于被挤占窗口开始时刻、且与当前已编排条目
     * （含刚插入的抢占方，排除被挤占条目自身）不重叠的最早窗口。
     */
    private ContactWindow findSuggestion(ScheduleEntry victim, ContactWindow incoming, Instant now) {
        List<ContactWindow> candidates = masterData.findWindowsAfter(victim.getStationId(),
                victim.getSatelliteId(), DayKeys.of(victim.getStartTime()),
                victim.getStartTime());
        List<ScheduleEntry> scheduled = schedules.findEntriesByVersion(victim.getVersionId())
                .stream()
                .filter(e -> e.getStatus() == EntryStatus.SCHEDULED)
                .filter(e -> e.getId() != victim.getId())
                .toList();
        for (ContactWindow candidate : candidates) {
            if (candidate.id() == victim.getWindowId() || candidate.id() == incoming.id()) {
                continue;
            }
            if (!candidate.end().isAfter(now)) {
                continue;
            }
            boolean overlap = scheduled.stream().anyMatch(e ->
                    candidate.start().isBefore(e.getEndTime())
                            && e.getStartTime().isBefore(candidate.end()));
            if (!overlap) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 确定申请写入的目标版本。
     */
    private ScheduleVersion resolveTargetVersion(long stationId, String dayKey,
                                                 boolean emergency, Instant now) {
        Optional<ScheduleVersion> latest = schedules.findLatestVersionForUpdate(stationId, dayKey);
        if (latest.isEmpty()) {
            long versionId = schedules.insertVersion(stationId, dayKey, 1, now);
            return schedules.findVersionById(versionId).orElseThrow();
        }
        ScheduleVersion version = latest.get();
        if (version.getStatus() == VersionStatus.PUBLISHED) {
            if (!emergency) {
                throw ApiException.conflict("DAY_FROZEN",
                        "该日编排已发布冻结（versionNo=" + version.getVersionNo()
                                + "），普通申请一律拒绝；紧急插入请使用 emergency=true 走新版本");
            }
            long versionId = schedules.insertVersion(stationId, dayKey,
                    version.getVersionNo() + 1, now);
            return schedules.findVersionById(versionId).orElseThrow();
        }
        return version;
    }

    /**
     * 按日发布：发布后冻结；重复发布返回幂等成功。
     */
    @Transactional
    public com.example.starter.schedule.web.dto.PublishResponseDto publish(String stationCode,
                                                                           String dayKey) {
        Instant now = Instant.now(clock);
        GroundStation station = masterData.findStationByCode(stationCode)
                .orElseThrow(() -> ApiException.notFound("地面站不存在: " + stationCode));
        lockStationDay(station.id(), dayKey);
        ScheduleVersion version = schedules.findLatestVersionForUpdate(station.id(), dayKey)
                .orElseThrow(() -> ApiException.notFound("该站该日不存在编排版本: " + dayKey));
        boolean alreadyPublished = version.getStatus() == VersionStatus.PUBLISHED;
        if (!alreadyPublished) {
            int rows = schedules.publishVersion(version.getId(), now);
            if (rows != 1) {
                throw new IllegalStateException("发布失败 versionId=" + version.getId());
            }
            version.setStatus(VersionStatus.PUBLISHED);
            version.setPublishedAt(now);
        }
        List<com.example.starter.schedule.web.dto.EntryViewDto> entries =
                schedules.findEntriesByVersion(version.getId()).stream()
                        .map(this::toEntryView).toList();
        return new com.example.starter.schedule.web.dto.PublishResponseDto(
                stationCode, dayKey, version.getVersionNo(), version.getStatus().name(),
                version.getPublishedAt(), alreadyPublished, entries);
    }

    /**
     * 撤销已编排申请；发布后的撤销一律拒绝。
     */
    @Transactional
    public com.example.starter.schedule.web.dto.EntryViewDto cancel(String requestId) {
        Instant now = Instant.now(clock);
        ScheduleEntry entry = schedules.findEntryByRequestId(requestId)
                .orElseThrow(() -> ApiException.notFound("编排申请不存在或未成功编排: " + requestId));
        if (entry.getStatus() != EntryStatus.SCHEDULED) {
            throw ApiException.conflict("ENTRY_NOT_ACTIVE",
                    "条目当前状态为 " + entry.getStatus() + "，仅已编排条目可撤销");
        }
        lockStationDay(entry.getStationId(), DayKeys.of(entry.getStartTime()));
        ScheduleVersion version = schedules.findVersionById(entry.getVersionId())
                .orElseThrow(() -> new IllegalStateException("版本缺失: " + entry.getVersionId()));
        if (version.getStatus() == VersionStatus.PUBLISHED) {
            throw ApiException.conflict("DAY_FROZEN",
                    "该日编排已发布冻结（versionNo=" + version.getVersionNo() + "），撤销一律拒绝");
        }
        int rows = schedules.markCancelled(entry.getId(), now);
        if (rows != 1) {
            throw ApiException.conflict("ENTRY_NOT_ACTIVE", "条目状态已变化，撤销失败");
        }
        ScheduleEntry updated = schedules.findEntryById(entry.getId()).orElseThrow();
        return toEntryView(updated);
    }

    /**
     * 查询同站某日全部版本（旧版本可查）。
     */
    @Transactional(readOnly = true)
    public List<com.example.starter.schedule.web.dto.VersionViewDto> versions(String stationCode,
                                                                              String dayKey) {
        GroundStation station = masterData.findStationByCode(stationCode)
                .orElseThrow(() -> ApiException.notFound("地面站不存在: " + stationCode));
        return schedules.findVersions(station.id(), dayKey).stream()
                .map(v -> new com.example.starter.schedule.web.dto.VersionViewDto(
                        v.getVersionNo(), v.getStatus().name(), v.getPublishedAt(),
                        schedules.findEntriesByVersion(v.getId()).stream()
                                .map(this::toEntryView).toList()))
                .toList();
    }

    /**
     * 同站同日事务串行化（锁表行锁，事务结束自动释放）。
     */
    private void lockStationDay(long stationId, String dayKey) {
        stationDayLocks.lock(stationId, dayKey);
    }

    private com.example.starter.schedule.web.dto.EntryViewDto toEntryView(ScheduleEntry e) {
        return new com.example.starter.schedule.web.dto.EntryViewDto(
                e.getId(), e.getRequestId(), e.getSatelliteId(), e.getWindowId(),
                e.getPriority(), e.getStatus().name(), e.getStartTime(), e.getEndTime(),
                e.getPreemptedByEntryId(), e.getSuggestionWindowId(),
                e.getSuggestionStartTime(), e.getSuggestionEndTime());
    }

    private List<ConflictItemDto> parseConflicts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, ConflictItemDto.class));
        } catch (JsonProcessingException e) {
            log.warn("冲突详情反序列化失败: {}", json, e);
            return List.of();
        }
    }

    private String writeConflicts(List<ConflictItemDto> conflicts) {
        try {
            return objectMapper.writeValueAsString(conflicts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冲突详情序列化失败", e);
        }
    }
}
