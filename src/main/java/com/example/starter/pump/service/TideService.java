package com.example.starter.pump.service;

import com.example.starter.pump.core.TimeProvider;
import com.example.starter.pump.domain.PumpStation;
import com.example.starter.pump.domain.PumpdownEvent;
import com.example.starter.pump.domain.TideSwitchCandidate;
import com.example.starter.pump.domain.TideZoneConfig;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.CatalogRepository;
import com.example.starter.pump.repo.PumpdownEventRepository;
import com.example.starter.pump.repo.TideConfigRepository;
import com.example.starter.pump.repo.TideReadingRepository;
import com.example.starter.pump.repo.TideSwitchRepository;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfigResult;
import com.example.starter.pump.web.dto.TideLevelRequest;
import com.example.starter.pump.web.dto.TideLevelResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 潮汐工况：分区顶托门槛配置、潮位上送与滞回判定、强排切换候选生成。
 *
 * <p>滞回规则：常规工况下潮位恰达或超过门槛进入强排；强排工况下潮位回落到
 * 门槛减0.2米以下（严格小于）才退出，恰在回落线仍保持强排。
 * 迟到较旧潮位只留证据不让当前工况倒退；已归档事件不可重开。
 * 所有写操作先锁配置行，并发潮位更新与切换按一致提交顺序推进。
 */
@Service
public class TideService {

    /** 退出滞回带宽：回落至门槛减0.2米以下才退出强排。 */
    static final BigDecimal EXIT_HYSTERESIS = new BigDecimal("0.2");

    private final CatalogRepository catalogRepository;
    private final PumpdownEventRepository eventRepository;
    private final TideConfigRepository configRepository;
    private final TideReadingRepository readingRepository;
    private final TideSwitchRepository switchRepository;
    private final TimeProvider timeProvider;

    public TideService(CatalogRepository catalogRepository,
                       PumpdownEventRepository eventRepository,
                       TideConfigRepository configRepository,
                       TideReadingRepository readingRepository,
                       TideSwitchRepository switchRepository,
                       TimeProvider timeProvider) {
        this.catalogRepository = catalogRepository;
        this.eventRepository = eventRepository;
        this.configRepository = configRepository;
        this.readingRepository = readingRepository;
        this.switchRepository = switchRepository;
        this.timeProvider = timeProvider;
    }

    /** 新建或修改分区潮汐控制配置（幂等首次执行体）。 */
    public TideConfigResult upsertConfig(TideConfigRequest request) {
        requireZone(request.zoneCode());
        LocalDateTime now = timeProvider.now();
        TideZoneConfig config = configRepository.findForUpdate(request.zoneCode()).orElse(null);
        if (config == null) {
            if (request.expectedVersion() != null) {
                throw ApiException.notFound("TIDE_CONFIG_NOT_FOUND")
                        .raise("分区 " + request.zoneCode() + " 未配置潮汐控制，无法按版本修改");
            }
            configRepository.create(request.zoneCode(), request.backwaterThreshold(), now);
            return new TideConfigResult(request.zoneCode(), request.backwaterThreshold(),
                    "NORMAL", 1, false);
        }
        requireVersion(request.expectedVersion(), config);
        int newVersion = config.version() + 1;
        configRepository.updateThreshold(request.zoneCode(), request.backwaterThreshold(),
                newVersion, now);
        return new TideConfigResult(request.zoneCode(), request.backwaterThreshold(),
                config.currentMode(), newVersion, false);
    }

    /** 潮位上送（幂等首次执行体）：去重、迟到证据、滞回判定与候选生成同事务。 */
    public TideLevelResult ingest(TideLevelRequest request) {
        requireZone(request.zoneCode());
        LocalDateTime receivedAt = timeProvider.now();
        LocalDateTime observedAtUtc = LocalDateTime.ofInstant(request.observedAt(), ZoneOffset.UTC);

        TideZoneConfig config = configRepository.findForUpdate(request.zoneCode())
                .orElseThrow(() -> ApiException.unprocessable("TIDE_NOT_CONFIGURED")
                        .raise("分区 " + request.zoneCode() + " 未配置潮汐控制，保持原行为"));
        requireVersion(request.expectedVersion(), config);

        // 分区＋UTC时刻去重：命中则原样返回，不产生任何新副作用
        Long existingId = readingRepository.findIdByZoneAndTime(request.zoneCode(), observedAtUtc);
        if (existingId != null) {
            return new TideLevelResult(existingId, request.zoneCode(), request.observedAt(),
                    request.tideLevel(), false, true, false, "NONE", null,
                    config.version(), false);
        }

        // 已归档事件不可重开：归档后潮位只留证据
        PumpdownEvent latest = eventRepository.findLatestByZone(request.zoneCode()).orElse(null);
        if (latest != null && latest.archived() && receivedAt.isAfter(latest.archivedAt())) {
            readingRepository.insertLate(request.zoneCode(), observedAtUtc, request.tideLevel(),
                    receivedAt, "ARCHIVED");
            return new TideLevelResult(null, request.zoneCode(), request.observedAt(),
                    request.tideLevel(), false, false, true, "NONE", null,
                    config.version(), false);
        }

        // 迟到较旧潮位：只留证据，不让当前工况倒退
        LocalDateTime latestObserved = readingRepository.latestObservedAt(request.zoneCode());
        if (latestObserved != null && observedAtUtc.isBefore(latestObserved)) {
            readingRepository.insertLate(request.zoneCode(), observedAtUtc, request.tideLevel(),
                    receivedAt, "OUT_OF_ORDER");
            return new TideLevelResult(null, request.zoneCode(), request.observedAt(),
                    request.tideLevel(), false, false, true, "NONE", null,
                    config.version(), false);
        }

        Long readingId = readingRepository.insert(request.zoneCode(), observedAtUtc,
                request.tideLevel(), receivedAt);
        if (readingId == null) {
            // 并发下分区＋时刻被抢占，按去重返回首次ID
            Long concurrentId = readingRepository.findIdByZoneAndTime(request.zoneCode(),
                    observedAtUtc);
            return new TideLevelResult(concurrentId, request.zoneCode(), request.observedAt(),
                    request.tideLevel(), false, true, false, "NONE", null,
                    config.version(), false);
        }

        String decision = decide(config, request.tideLevel());
        Long candidateId = applyDecision(config, request, readingId, observedAtUtc, decision,
                receivedAt);
        return new TideLevelResult(readingId, request.zoneCode(), request.observedAt(),
                request.tideLevel(), true, false, false, decision, candidateId,
                config.version(), false);
    }

    /** 滞回判定：恰达门槛进入；严格低于门槛减0.2才退出，恰在回落线保持。 */
    private String decide(TideZoneConfig config, BigDecimal tideLevel) {
        BigDecimal threshold = config.backwaterThreshold();
        if (!config.forced() && tideLevel.compareTo(threshold) >= 0) {
            return "ENTER_FORCED";
        }
        if (config.forced()
                && tideLevel.compareTo(threshold.subtract(EXIT_HYSTERESIS)) < 0) {
            return "EXIT_FORCED";
        }
        return "NONE";
    }

    /**
     * 应用判定结果：生成或复用候选序列；新数据使待确认候选前提失效时将其取代。
     *
     * @return 候选序列ID，无候选为 null
     */
    private Long applyDecision(TideZoneConfig config, TideLevelRequest request, Long readingId,
                               LocalDateTime observedAtUtc, String decision,
                               LocalDateTime receivedAt) {
        TideSwitchCandidate pending = switchRepository
                .findPendingByZone(request.zoneCode()).orElse(null);
        if ("NONE".equals(decision)) {
            // 前提失效的待确认候选被新数据取代，避免按过期潮位确认切换
            if (pending != null && premiseFailing(config, pending, request.tideLevel())) {
                switchRepository.updateStatus(pending.id(), "SUPERSEDED", null);
            }
            return null;
        }
        String targetMode = "ENTER_FORCED".equals(decision) ? "FORCED" : "NORMAL";
        if (pending != null && pending.targetMode().equals(targetMode)) {
            return pending.id();
        }
        if (pending != null) {
            switchRepository.updateStatus(pending.id(), "SUPERSEDED", null);
        }
        Long candidateId = switchRepository.insertCandidate(request.requestId(),
                request.zoneCode(), targetMode, readingId, request.tideLevel(), observedAtUtc,
                config.backwaterThreshold(), config.version(), receivedAt);
        // 固定全部相关站闸门目标：进入强排取站点联动开度，退出强排归零
        List<PumpStation> stations = catalogRepository.stationsOfZone(request.zoneCode());
        for (PumpStation station : stations) {
            BigDecimal target = "FORCED".equals(targetMode) ? station.gateStartOpening()
                    : BigDecimal.ZERO;
            switchRepository.insertCandidateStation(candidateId, station.code(), target);
        }
        return candidateId;
    }

    /** 待确认候选的触发前提是否已被新潮位推翻。 */
    private boolean premiseFailing(TideZoneConfig config, TideSwitchCandidate pending,
                                   BigDecimal tideLevel) {
        BigDecimal threshold = config.backwaterThreshold();
        if ("FORCED".equals(pending.targetMode())) {
            // 进入前提：潮位 >= 门槛；跌破门槛即失效
            return tideLevel.compareTo(threshold) < 0;
        }
        // 退出前提：潮位 < 门槛-0.2；回到回落线及以上即失效
        return tideLevel.compareTo(threshold.subtract(EXIT_HYSTERESIS)) >= 0;
    }

    private void requireZone(String zoneCode) {
        if (!catalogRepository.zoneExists(zoneCode)) {
            throw ApiException.notFound("ZONE_NOT_FOUND")
                    .raise("流域分区不存在: " + zoneCode);
        }
    }

    /** 版本失配拒绝并提供当前最新版本。 */
    static void requireVersion(Integer expectedVersion, TideZoneConfig config) {
        if (expectedVersion == null || expectedVersion != config.version()) {
            throw ApiException.versionConflict(
                    "工况版本失配：期望 " + expectedVersion + "，当前 " + config.version(),
                    config.version());
        }
    }
}
