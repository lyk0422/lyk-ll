package com.example.starter.pump.service;

import com.example.starter.pump.core.TimeProvider;
import com.example.starter.pump.domain.PumpStation;
import com.example.starter.pump.domain.TideControlConfig;
import com.example.starter.pump.domain.TideReading;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.CatalogRepository;
import com.example.starter.pump.repo.LevelReadingRepository;
import com.example.starter.pump.repo.StrongDrainRepository;
import com.example.starter.pump.repo.TideRepository;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.CandidateConfirmResult;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.GateReceiptResult;
import com.example.starter.pump.web.dto.StrongDrainView;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfigResult;
import com.example.starter.pump.web.dto.TideReadingRequest;
import com.example.starter.pump.web.dto.TideReadingResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 潮汐顶托工况切换与强排联锁。
 *
 * <p>工况滞后回线：潮位 ≥ 门槛进入强排（恰等进入）；潮位严格低于 门槛-0.2米 才退出
 * （恰在回落线保持强排）；回落线与门槛之间维持当前工况。
 *
 * <p>切换两段式：潮位跨越边界先生成候选（固定潮位版本与全部相关站闸门目标），
 * 各站闸门合成到位回执齐全后确认。确认在单事务内校验旧分区序列版本、所有站无其他占用、
 * 回执齐全，才原子终结旧序列、启用新序列并写各站任务；任一不到位整体回滚、零切换。
 * 启机10分钟间隔、预降目标停泵与安全下限不因为强排放宽。
 */
@Service
public class TideStrongDrainService {

    /** 相邻两次启机最小间隔（不因为强排放宽）。 */
    static final Duration MIN_START_INTERVAL = Duration.ofMinutes(10);
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    static final ZoneOffset UTC = ZoneOffset.UTC;

    private final TideRepository tideRepository;
    private final StrongDrainRepository strongRepository;
    private final CatalogRepository catalogRepository;
    private final LevelReadingRepository levelRepository;
    private final TimeProvider timeProvider;

    public TideStrongDrainService(TideRepository tideRepository,
                                  StrongDrainRepository strongRepository,
                                  CatalogRepository catalogRepository,
                                  LevelReadingRepository levelRepository,
                                  TimeProvider timeProvider) {
        this.tideRepository = tideRepository;
        this.strongRepository = strongRepository;
        this.catalogRepository = catalogRepository;
        this.levelRepository = levelRepository;
        this.timeProvider = timeProvider;
    }

    // ============ 配置 ============

    /** 首次新增（expectedVersion=0，成功版本1）或条件修改（版本匹配才加1）。 */
    public TideConfigResult upsertConfig(TideConfigRequest request) {
        requireZone(request.zoneCode());
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw ApiException.unprocessable("INVALID_EXPECTED_VERSION")
                    .raise("expectedVersion 非法：首次新增传0，修改传当前版本（>=1）");
        }
        LocalDateTime now = timeProvider.now();
        Optional<TideControlConfig> existing = tideRepository.findConfig(request.zoneCode());

        if (existing.isEmpty()) {
            if (request.expectedVersion() != 0) {
                throw ApiException.versionMismatch("CONFIG_VERSION_MISMATCH",
                        "分区 " + request.zoneCode() + " 尚未配置潮汐控制，新增须传 expectedVersion=0", 0);
            }
            TideControlConfig created = tideRepository.insertConfig(request.zoneCode(),
                    request.thresholdLevel(), TideControlConfig.BACKOFF, now);
            strongRepository.insertTrace(request.zoneCode(), null, "CONFIG_CHANGED",
                    "NORMAL", "NORMAL", null,
                    "首次配置门槛=" + created.thresholdLevel() + "米，版本=1", now);
            return new TideConfigResult(created.zoneCode(), created.thresholdLevel(),
                    created.backoff(), created.regimeVersion(), true, false);
        }

        TideControlConfig current = existing.get();
        if (request.expectedVersion() != current.regimeVersion()) {
            throw ApiException.versionMismatch("CONFIG_VERSION_MISMATCH",
                    "分区 " + request.zoneCode() + " 工况配置版本失配：期望 "
                            + request.expectedVersion() + "，当前 " + current.regimeVersion(),
                    current.regimeVersion());
        }
        Integer newVersion = tideRepository
                .updateConfigIfVersion(request.zoneCode(), request.thresholdLevel(),
                        TideControlConfig.BACKOFF, current.regimeVersion(), now)
                .orElseThrow(() -> ApiException.versionMismatch("CONFIG_VERSION_MISMATCH",
                        "并发修改导致分区 " + request.zoneCode() + " 配置版本失配",
                        current.regimeVersion()));
        strongRepository.insertTrace(request.zoneCode(), null, "CONFIG_CHANGED",
                "NORMAL", "NORMAL", null,
                "门槛改为" + request.thresholdLevel() + "米，版本=" + newVersion, now);
        return new TideConfigResult(request.zoneCode(), request.thresholdLevel(),
                TideControlConfig.BACKOFF, newVersion, false, false);
    }

    // ============ 潮位 ============

    /** 潮位上送：去重、迟到旧潮位只留证据、跨边界生成候选。 */
    public TideReadingResult ingest(TideReadingRequest request) {
        requireZone(request.zoneCode());
        TideControlConfig config = requireConfig(request.zoneCode());
        LocalDateTime now = timeProvider.now();
        OffsetDateTime atUtc = request.tideTime().withOffsetSameInstant(UTC);

        TideReading latestBefore = tideRepository.findLatestReading(request.zoneCode()).orElse(null);

        // 迟到较旧潮位：只写证据表，不生成新版本、不让当前工况倒退
        if (latestBefore != null && atUtc.isBefore(latestBefore.tideTime())) {
            tideRepository.insertLate(request.zoneCode(), atUtc, request.tideLevel(), now);
            String mode = strongRepository.findActiveSequenceId(request.zoneCode()).isPresent()
                    ? "STRONG" : "NORMAL";
            return new TideReadingResult(latestBefore.id(), request.zoneCode(), atUtc,
                    request.tideLevel(), false, true, false, mode, null, null, false);
        }

        Optional<TideReading> inserted = tideRepository.insertReading(request.zoneCode(), atUtc,
                request.tideLevel(), now);
        if (inserted.isEmpty()) {
            // 分区+UTC时刻去重：返回首次版本，不产生新副作用（按瞬时时刻比较，忽略偏移表达）
            TideReading first = tideRepository.loadReadings(request.zoneCode()).stream()
                    .filter(r -> r.tideTime().toInstant().equals(atUtc.toInstant()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("潮位去重重查失败: " + atUtc));
            String mode = strongRepository.findActiveSequenceId(request.zoneCode()).isPresent()
                    ? "STRONG" : "NORMAL";
            return new TideReadingResult(first.id(), request.zoneCode(), atUtc,
                    first.tideLevel(), false, false, true, mode, null, null, false);
        }

        TideReading reading = inserted.get();
        return evaluateRegime(config, reading, now, false);
    }

    /** 依据最新潮位与滞后回线求目标工况，跨边界则生成（或复用同方向待确认）候选。 */
    private TideReadingResult evaluateRegime(TideControlConfig config, TideReading reading,
                                             LocalDateTime now, boolean replayed) {
        boolean strongNow = strongRepository.findActiveSequenceId(config.zoneCode()).isPresent();
        String desired = desiredMode(config, reading.tideLevel(), strongNow);
        String current = strongNow ? "STRONG" : "NORMAL";

        Long candidateId = null;
        String direction = null;
        if (!desired.equals(current)) {
            direction = "STRONG".equals(desired) ? "ENTER" : "EXIT";
            candidateId = reuseOrCreateCandidate(config, reading, direction, now);
        }
        return new TideReadingResult(reading.id(), config.zoneCode(), reading.tideTime(),
                reading.tideLevel(), true, false, false, desired, candidateId, direction, replayed);
    }

    /**
     * 同方向已有待确认候选（且固定的旧在强排序列与当前一致）时复用，避免连续同向潮位重复建候选；
     * 否则生成新候选并固化潮位版本与各站闸门目标。
     */
    private Long reuseOrCreateCandidate(TideControlConfig config, TideReading reading,
                                        String direction, LocalDateTime now) {
        Long activeId = strongRepository.findActiveSequenceId(config.zoneCode()).orElse(null);
        return strongRepository.findOpenCandidate(config.zoneCode())
                .filter(c -> c.direction().equals(direction))
                .filter(c -> java.util.Objects.equals(c.expectedActiveSequenceId(), activeId))
                .map(StrongDrainView.SequenceRow::id)
                .orElseGet(() -> createCandidate(config, reading, direction, activeId, now));
    }

    /** 滞后回线：≥门槛进入（恰等进入）；严格低于门槛-0.2退出（恰在回落线保持）；其间维持现状。 */
    private String desiredMode(TideControlConfig config, BigDecimal level, boolean strongNow) {
        if (level.compareTo(config.thresholdLevel()) >= 0) {
            return "STRONG";
        }
        if (level.compareTo(config.exitLine()) < 0) {
            return "NORMAL";
        }
        return strongNow ? "STRONG" : "NORMAL";
    }

    /** 生成候选：固定潮位版本、方向、旧在强排序列与全部相关站闸门目标快照。 */
    private Long createCandidate(TideControlConfig config, TideReading reading, String direction,
                                 Long activeId, LocalDateTime now) {
        List<PumpStation> stations = requireStations(config.zoneCode());
        Long sequenceId = strongRepository.insertSequence(config.zoneCode(), reading.id(),
                reading.tideLevel(), direction, config.regimeVersion(), activeId,
                reading.tideTime(), now);
        for (PumpStation station : stations) {
            strongRepository.insertTarget(sequenceId, station.code(), station.gateStartOpening());
        }
        String from = "ENTER".equals(direction) ? "NORMAL" : "STRONG";
        String to = "ENTER".equals(direction) ? "STRONG" : "NORMAL";
        strongRepository.insertTrace(config.zoneCode(), reading.id(), "CANDIDATE_CREATED",
                from, to, sequenceId,
                "候选序列固定潮位版本" + reading.id() + "，方向" + direction
                        + "，闸门目标" + stations.size() + "站", now);
        return sequenceId;
    }

    // ============ 闸门到位回执 ============

    /** 回执上送：仅受理属于候选、站点在目标内且开度与目标一致的到位回执。 */
    public GateReceiptResult receipt(GateReceiptRequest request) {
        StrongDrainView.SequenceRow sequence = requireSequence(request.sequenceId());
        if (!"CANDIDATE".equals(sequence.state())) {
            throw ApiException.unprocessable("SEQUENCE_NOT_CANDIDATE")
                    .raise("强排序列 " + sequence.id() + " 状态为 " + sequence.state()
                            + "，仅候选序列可受理闸门到位回执；旧序列回执只归旧任务");
        }
        List<StrongDrainView.TargetItem> targets = strongRepository.findTargets(sequence.id());
        StrongDrainView.TargetItem target = targets.stream()
                .filter(t -> t.stationCode().equals(request.stationCode()))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("STATION_NOT_IN_CANDIDATE")
                        .raise("泵站 " + request.stationCode() + " 不在候选序列 "
                                + sequence.id() + " 的相关站范围内"));

        List<StrongDrainView.ReceiptItem> existing = strongRepository.findReceipts(sequence.id());
        Optional<StrongDrainView.ReceiptItem> sameStation = existing.stream()
                .filter(r -> r.stationCode().equals(request.stationCode())).findFirst();
        if (sameStation.isPresent()) {
            if (sameStation.get().opening().compareTo(request.opening()) != 0) {
                throw ApiException.unprocessable("GATE_RECEIPT_CONFLICT")
                        .raise("泵站 " + request.stationCode() + " 已有到位开度 "
                                + sameStation.get().opening() + "，与本次 " + request.opening()
                                + " 不一致");
            }
            return new GateReceiptResult(sequence.id(), request.stationCode(),
                    request.opening(), request.receiptTime().withOffsetSameInstant(UTC), false);
        }
        if (request.opening().compareTo(target.gateTarget()) != 0) {
            throw ApiException.unprocessable("GATE_NOT_IN_POSITION")
                    .raise("泵站 " + request.stationCode() + " 闸门未到位：实际 "
                            + request.opening() + "，候选目标 " + target.gateTarget());
        }
        strongRepository.insertReceipt(sequence.id(), request.stationCode(), request.opening(),
                timeProvider.now());
        return new GateReceiptResult(sequence.id(), request.stationCode(), request.opening(),
                request.receiptTime().withOffsetSameInstant(UTC), false);
    }

    // ============ 确认（联锁切换） ============

    /** 确认候选：单事务原子切换，任一前置不到位零切换。 */
    public CandidateConfirmResult confirm(CandidateConfirmRequest request) {
        // 锁定分区配置行，串行化同分区并发确认（配置版本即提交顺序依据）
        TideControlConfig config = lockConfigAndCheckVersion(request.sequenceId(),
                request.expectedVersion());
        strongRepository.lockSequence(request.sequenceId());
        StrongDrainView.SequenceRow candidate = requireSequence(request.sequenceId());

        if (!"CANDIDATE".equals(candidate.state())) {
            throw ApiException.unprocessable("CANDIDATE_NOT_OPEN")
                    .raise("候选序列 " + candidate.id() + " 状态为 " + candidate.state()
                            + "，不可再确认；已归档/终结事件不可重开");
        }
        if (candidate.regimeVersion() != request.expectedVersion()) {
            throw ApiException.versionMismatch("REGIME_VERSION_MISMATCH",
                    "候选序列生成于工况版本 " + candidate.regimeVersion() + "，确认期望 "
                            + request.expectedVersion(), config.regimeVersion());
        }

        List<PumpStation> stations = requireStations(config.zoneCode());
        boolean enter = "ENTER".equals(candidate.direction());

        validateOldSequenceVersion(config.zoneCode(), candidate, enter);
        validateReceiptsComplete(candidate, stations);
        validateOccupancyAndSafety(candidate, stations, enter);

        // 原子：终结旧序列（条件更新，失败即版本已变）-> 启用候选 -> 写各站任务
        Long superseded = strongRepository.findActiveSequenceId(config.zoneCode()).orElse(null);
        int ended = strongRepository.supersedeIfActive(config.zoneCode(),
                enter ? null : candidate.expectedActiveSequenceId());
        if (ended == 0) {
            throw ApiException.versionMismatch("ACTIVE_SEQUENCE_CHANGED",
                    "分区 " + config.zoneCode() + " 在强排序列已被并发切换，旧序列版本失配",
                    config.regimeVersion());
        }
        // 进入：候选置 ACTIVE（成为在强排序列）；退出：候选置 COMPLETED 终态（分区恢复常规）
        int stateRows = enter ? strongRepository.activateIfCandidate(candidate.id())
                : strongRepository.completeIfCandidate(candidate.id());
        if (stateRows == 0) {
            throw ApiException.unprocessable("CANDIDATE_NOT_OPEN")
                    .raise("候选序列 " + candidate.id() + " 已被并发处理，本次零切换");
        }
        String finalState = enter ? "ACTIVE" : "COMPLETED";

        String action = enter ? "START" : "STOP";
        String reason = enter ? "STRONG_DRAIN_ENTER" : "STRONG_DRAIN_EXIT";
        List<CandidateConfirmResult.TaskItem> tasks = new ArrayList<>();
        for (PumpStation station : stations) {
            Long taskId = strongRepository.insertTask(candidate.id(), station.code(), action,
                    candidate.taskTime(), reason);
            if (taskId == null) {
                // 站点＋时刻被占用：整事务回滚，绝不允许一半站先启机
                throw ApiException.unprocessable("STATION_TIME_CONFLICT")
                        .raise("泵站 " + station.code() + " 在 " + candidate.taskTime()
                                + " 已被占用，联锁切换零受理");
            }
            tasks.add(new CandidateConfirmResult.TaskItem(station.code(), action,
                    candidate.taskTime()));
        }

        strongRepository.insertTrace(config.zoneCode(), candidate.tideVersion(),
                enter ? "SWITCH_ON" : "SWITCH_OFF",
                enter ? "NORMAL" : "STRONG", enter ? "STRONG" : "NORMAL", candidate.id(),
                "确认切换" + (enter ? "进入" : "退出") + "强排，原子写" + stations.size()
                        + "站任务" + (superseded != null ? "，终结旧序列" + superseded : ""),
                timeProvider.now());

        return new CandidateConfirmResult(candidate.id(), config.zoneCode(), candidate.direction(),
                finalState, candidate.tideVersion(), config.regimeVersion(),
                enter ? null : candidate.expectedActiveSequenceId(), tasks, false);
    }

    /** 锁配置行并核对确认期望版本；同时核对候选属于该配置分区。 */
    private TideControlConfig lockConfigAndCheckVersion(Long sequenceId, Integer expectedVersion) {
        StrongDrainView.SequenceRow sequence = requireSequence(sequenceId);
        TideControlConfig config = tideRepository.lockConfig(sequence.zoneCode())
                .orElseThrow(() -> ApiException.unprocessable("TIDE_CONTROL_NOT_CONFIGURED")
                        .raise("分区 " + sequence.zoneCode() + " 未配置潮汐控制"));
        if (expectedVersion == null || expectedVersion <= 0) {
            throw ApiException.unprocessable("INVALID_EXPECTED_VERSION")
                    .raise("expectedVersion 非法：确认须携带候选生成时的工况版本(>=1)");
        }
        if (expectedVersion != config.regimeVersion()) {
            throw ApiException.versionMismatch("REGIME_VERSION_MISMATCH",
                    "分区 " + config.zoneCode() + " 工况版本失配：期望 " + expectedVersion
                            + "，当前 " + config.regimeVersion(), config.regimeVersion());
        }
        return config;
    }

    /** 校验旧分区序列版本：进入前须无在强排序列；退出前候选期望的旧序列须仍在强排。 */
    private void validateOldSequenceVersion(String zoneCode, StrongDrainView.SequenceRow candidate,
                                            boolean enter) {
        Long activeId = strongRepository.findActiveSequenceId(zoneCode).orElse(null);
        if (enter) {
            if (activeId != null || candidate.expectedActiveSequenceId() != null) {
                throw ApiException.unprocessable("ALREADY_STRONG_DRAIN")
                        .raise("分区 " + zoneCode + " 已在强排（序列" + activeId
                                + "），进入候选的旧序列版本失配");
            }
        } else {
            if (activeId == null) {
                throw ApiException.unprocessable("NOT_IN_STRONG_DRAIN")
                        .raise("分区 " + zoneCode + " 当前不在强排，退出候选的旧序列版本失配");
            }
            if (candidate.expectedActiveSequenceId() == null
                    || !candidate.expectedActiveSequenceId().equals(activeId)) {
                throw ApiException.versionMismatch("ACTIVE_SEQUENCE_CHANGED",
                        "退出候选固定的旧序列 " + candidate.expectedActiveSequenceId()
                                + " 已非当前在强排序列 " + activeId, null);
            }
        }
    }

    /** 闸门合成到位回执齐全：候选每个相关站都须有开度与目标一致的回执。 */
    private void validateReceiptsComplete(StrongDrainView.SequenceRow candidate,
                                          List<PumpStation> stations) {
        List<StrongDrainView.ReceiptItem> receipts = strongRepository.findReceipts(candidate.id());
        for (PumpStation station : stations) {
            StrongDrainView.ReceiptItem receipt = receipts.stream()
                    .filter(r -> r.stationCode().equals(station.code()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.unprocessable("GATE_RECEIPT_MISSING")
                            .raise("泵站 " + station.code() + " 闸门到位回执缺失，联锁切换零受理"));
            StrongDrainView.TargetItem target = strongRepository.findTargets(candidate.id()).stream()
                    .filter(t -> t.stationCode().equals(station.code()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("候选缺少站点目标: " + station.code()));
            if (receipt.opening().compareTo(target.gateTarget()) != 0) {
                throw ApiException.unprocessable("GATE_NOT_IN_POSITION")
                        .raise("泵站 " + station.code() + " 闸门未到位：回执 " + receipt.opening()
                                + "，目标 " + target.gateTarget());
            }
        }
    }

    /**
     * 占用与安全校验：进入时各站不得被占用、距上次启机不少于10分钟，
     * 且不放宽预降目标停泵与安全下限；退出时各站须正由本强排序列启泵运行。
     */
    private void validateOccupancyAndSafety(StrongDrainView.SequenceRow candidate,
                                            List<PumpStation> stations, boolean enter) {
        LocalDateTime taskLocal = candidate.taskTime().atZoneSameInstant(SHANGHAI)
                .toLocalDateTime();
        for (PumpStation station : stations) {
            if (enter) {
                assertNotOccupied(station.code());
                assertStartInterval(station.code(), candidate.taskTime(), taskLocal);
                assertLevelAllowsStart(station);
            } else {
                String strongAction = strongRepository.latestTaskAction(station.code()).orElse(null);
                if (!"START".equals(strongAction)) {
                    throw ApiException.unprocessable("STATION_NOT_RUNNING_STRONG")
                            .raise("泵站 " + station.code() + " 未处于强排运行，无法退出停泵");
                }
            }
        }
    }

    /** 进入强排：强排任务体系与原预降任务体系均不得处于启泵占用。 */
    private void assertNotOccupied(String stationCode) {
        if ("START".equals(strongRepository.latestTaskAction(stationCode).orElse(null))) {
            throw ApiException.unprocessable("STATION_OCCUPIED_STRONG")
                    .raise("泵站 " + stationCode + " 已在强排运行，禁止重复占用");
        }
        if ("START".equals(strongRepository.latestOriginalTaskAction(stationCode).orElse(null))) {
            throw ApiException.unprocessable("STATION_OCCUPIED_ORIGINAL")
                    .raise("泵站 " + stationCode + " 在原预降任务中仍启泵占用，强排不得抢占");
        }
    }

    /** 启机间隔（强排与原预降启泵都计），不因为强排放宽。 */
    private void assertStartInterval(String stationCode, OffsetDateTime taskUtc,
                                     LocalDateTime taskLocal) {
        strongRepository.latestStrongStartTimeBefore(stationCode, taskUtc).ifPresent(last -> {
            if (Duration.between(last, taskUtc).compareTo(MIN_START_INTERVAL) < 0) {
                throw ApiException.unprocessable("START_INTERVAL_TOO_SHORT")
                        .raise("泵站 " + stationCode + " 距上次强排启泵不足10分钟");
            }
        });
        strongRepository.latestOriginalStartTimeBefore(stationCode, taskLocal).ifPresent(last -> {
            if (Duration.between(last, taskLocal).compareTo(MIN_START_INTERVAL) < 0) {
                throw ApiException.unprocessable("START_INTERVAL_TOO_SHORT")
                        .raise("泵站 " + stationCode + " 距上次预降启泵不足10分钟，不因为强排放宽");
            }
        });
    }

    /** 预降目标停泵与安全下限不因为强排放宽：已有最新液位达到停泵条件则拒绝启泵。 */
    private void assertLevelAllowsStart(PumpStation station) {
        List<com.example.starter.pump.web.dto.ReplayResult.LevelPoint> curve =
                levelRepository.loadCurveLatestOfStation(station.code());
        if (curve.isEmpty()) {
            return;
        }
        BigDecimal latest = curve.get(0).level();
        if (latest.compareTo(station.safeLowLevel()) < 0
                || latest.compareTo(station.targetLevel()) <= 0) {
            throw ApiException.unprocessable("STRONG_START_BLOCKED_BY_LEVEL")
                    .raise("泵站 " + station.code() + " 最新水位 " + latest
                            + " 已达预降目标/安全下限，停泵约束不因为强排放宽");
        }
    }

    // ============ 查询 ============

    /** 工况总览：潮位、当前工况、候选/生效序列与轨迹；未配置分区 configured=false。 */
    public StrongDrainView view(String zoneCode) {
        Optional<TideControlConfig> configOpt = tideRepository.findConfig(zoneCode);
        if (configOpt.isEmpty()) {
            return new StrongDrainView(zoneCode, false, null, null, null, "NORMAL",
                    null, null, List.of(), List.of());
        }
        TideControlConfig config = configOpt.get();
        Long activeId = strongRepository.findActiveSequenceId(zoneCode).orElse(null);
        StrongDrainView.SequenceDetail active = activeId == null ? null
                : loadDetail(strongRepository.findSequenceById(activeId).orElseThrow());
        StrongDrainView.SequenceDetail open = strongRepository.findOpenCandidate(zoneCode)
                .map(this::loadDetail).orElse(null);
        List<StrongDrainView.TidePoint> points = tideRepository.loadReadings(zoneCode).stream()
                .map(r -> new StrongDrainView.TidePoint(r.id(), r.tideTime(), r.tideLevel(),
                        r.receivedAt()))
                .toList();
        return new StrongDrainView(zoneCode, true, config.thresholdLevel(), config.exitLine(),
                config.regimeVersion(), activeId == null ? "NORMAL" : "STRONG", active, open,
                points, strongRepository.findTrace(zoneCode));
    }

    private StrongDrainView.SequenceDetail loadDetail(StrongDrainView.SequenceRow row) {
        return new StrongDrainView.SequenceDetail(row.id(), row.tideVersion(), row.tideLevel(),
                row.direction(), row.regimeVersion(), row.expectedActiveSequenceId(), row.state(),
                row.taskTime(), row.createdAt(), strongRepository.findTargets(row.id()),
                strongRepository.findReceipts(row.id()), strongRepository.findTasks(row.id()));
    }

    // ============ 装配复用 ============

    /** 重放潮位结果时保留候选信息（首次结果已冻结，此处仅标记 replayed）。 */
    public TideReadingResult markReplayed(TideReadingResult value) {
        return new TideReadingResult(value.tideVersion(), value.zoneCode(), value.tideTime(),
                value.tideLevel(), value.stored(), value.late(), value.duplicate(),
                value.desiredMode(), value.candidateId(), value.candidateDirection(), true);
    }

    private void requireZone(String zoneCode) {
        if (!catalogRepository.zoneExists(zoneCode)) {
            throw ApiException.notFound("ZONE_NOT_FOUND")
                    .raise("流域分区不存在: " + zoneCode);
        }
    }

    private TideControlConfig requireConfig(String zoneCode) {
        return tideRepository.findConfig(zoneCode)
                .orElseThrow(() -> ApiException.unprocessable("TIDE_CONTROL_NOT_CONFIGURED")
                        .raise("分区 " + zoneCode + " 未配置潮汐控制，保持原预降行为"));
    }

    private StrongDrainView.SequenceRow requireSequence(Long sequenceId) {
        return strongRepository.findSequenceById(sequenceId)
                .orElseThrow(() -> ApiException.notFound("SEQUENCE_NOT_FOUND")
                        .raise("强排序列不存在: " + sequenceId));
    }

    private List<PumpStation> requireStations(String zoneCode) {
        List<PumpStation> stations = catalogRepository.stationsOfZone(zoneCode);
        if (stations.isEmpty()) {
            throw ApiException.unprocessable("ZONE_WITHOUT_STATION")
                    .raise("分区下无泵站，无法强排: " + zoneCode);
        }
        return stations;
    }
}
