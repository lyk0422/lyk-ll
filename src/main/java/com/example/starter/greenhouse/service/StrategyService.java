package com.example.starter.greenhouse.service;

import com.example.starter.greenhouse.domain.IdempotentBizType;
import com.example.starter.greenhouse.domain.StrategyStatus;
import com.example.starter.greenhouse.dto.ActivationResponse;
import com.example.starter.greenhouse.dto.StrategyDraftRequest;
import com.example.starter.greenhouse.dto.StrategyResponse;
import com.example.starter.greenhouse.po.ClimateStrategy;
import com.example.starter.greenhouse.po.Crop;
import com.example.starter.greenhouse.po.StrategyChangeLog;
import com.example.starter.greenhouse.po.Zone;
import com.example.starter.greenhouse.po.ZoneActiveStrategy;
import com.example.starter.greenhouse.repo.CropRepository;
import com.example.starter.greenhouse.repo.StrategyRepository;
import com.example.starter.greenhouse.repo.ZoneRepository;
import com.example.starter.greenhouse.support.Fingerprints;
import com.example.starter.greenhouse.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 气候策略“编制→校验→下发”领域服务。
 */
@Service
public class StrategyService {

    /** 昼/夜设定点温差上限（℃），恰 8.00 合法。 */
    static final BigDecimal MAX_DAY_NIGHT_DELTA = new BigDecimal("8.00");
    /** 同分区相邻两次策略变更最小间隔，恰满 30 分钟允许。 */
    static final Duration MIN_CHANGE_INTERVAL = Duration.ofMinutes(30);

    private final StrategyRepository strategyRepository;
    private final CropRepository cropRepository;
    private final ZoneRepository zoneRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public StrategyService(StrategyRepository strategyRepository,
                           CropRepository cropRepository,
                           ZoneRepository zoneRepository,
                           IdempotencyService idempotencyService,
                           Clock clock) {
        this.strategyRepository = strategyRepository;
        this.cropRepository = cropRepository;
        this.zoneRepository = zoneRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /** 编制：落 DRAFT 策略，不做窗口校验（窗口在“校验”环节处理）。 */
    @Transactional
    public StrategyResponse draft(StrategyDraftRequest req) {
        Crop crop = cropRepository.findByCode(req.cropCode())
                .orElseThrow(() -> ApiException.notFound("作物不存在: " + req.cropCode()));
        Zone zone = zoneRepository.findByCode(req.zoneCode())
                .orElseThrow(() -> ApiException.notFound("分区不存在: " + req.zoneCode()));
        if (strategyRepository.findByCode(req.strategyCode()).isPresent()) {
            throw ApiException.conflict("策略代码已存在: " + req.strategyCode());
        }
        if (req.dayTempTolerance().signum() < 0 || req.nightTempTolerance().signum() < 0
                || req.dayHumidityTolerance().signum() < 0 || req.nightHumidityTolerance().signum() < 0) {
            throw ApiException.badRequest("容差不得为负");
        }

        ClimateStrategy s = new ClimateStrategy();
        s.setStrategyCode(req.strategyCode());
        s.setCropId(crop.getId());
        s.setZoneId(zone.getId());
        s.setDayTempTarget(req.dayTempTarget());
        s.setDayTempTolerance(req.dayTempTolerance());
        s.setDayHumidityTarget(req.dayHumidityTarget());
        s.setDayHumidityTolerance(req.dayHumidityTolerance());
        s.setNightTempTarget(req.nightTempTarget());
        s.setNightTempTolerance(req.nightTempTolerance());
        s.setNightHumidityTarget(req.nightHumidityTarget());
        s.setNightHumidityTolerance(req.nightHumidityTolerance());
        s.setStatus(StrategyStatus.DRAFT);
        s.setCreatedAt(Instant.now(clock));
        try {
            strategyRepository.insert(s);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("策略代码已存在: " + req.strategyCode());
        }
        return toResponse(s, crop.getCropCode(), zone.getZoneCode());
    }

    /** 校验：设定点窗口（含边界）、昼夜温差（含 8℃）；通过后 DRAFT→VALIDATED。 */
    @Transactional
    public StrategyResponse validate(String strategyCode) {
        ClimateStrategy s = mustGet(strategyCode);
        Crop crop = cropRepository.findById(s.getCropId())
                .orElseThrow(() -> ApiException.notFound("作物不存在"));
        if (s.getStatus() != StrategyStatus.DRAFT) {
            throw ApiException.unprocessable("仅 DRAFT 策略可校验，当前状态: " + s.getStatus());
        }

        List<String> violations = new java.util.ArrayList<>();
        checkWindow(violations, "白天温度", s.getDayTempTarget(), crop.getDayTempMin(), crop.getDayTempMax());
        checkWindow(violations, "白天湿度", s.getDayHumidityTarget(), crop.getDayHumidityMin(), crop.getDayHumidityMax());
        checkWindow(violations, "夜间温度", s.getNightTempTarget(), crop.getNightTempMin(), crop.getNightTempMax());
        checkWindow(violations, "夜间湿度", s.getNightHumidityTarget(), crop.getNightHumidityMin(), crop.getNightHumidityMax());
        BigDecimal delta = s.getDayTempTarget().subtract(s.getNightTempTarget()).abs();
        if (delta.compareTo(MAX_DAY_NIGHT_DELTA) > 0) {
            violations.add("昼夜温度设定差 " + delta + "℃ 超过上限 8.00℃");
        }

        Instant now = Instant.now(clock);
        if (!violations.isEmpty()) {
            String message = "校验失败: " + String.join("; ", violations);
            strategyRepository.updateStatus(s.getId(), StrategyStatus.DRAFT, message, now);
            throw ApiException.unprocessable(message);
        }
        strategyRepository.updateStatus(s.getId(), StrategyStatus.VALIDATED, "校验通过", now);
        s.setStatus(StrategyStatus.VALIDATED);
        s.setValidationMessage("校验通过");
        Zone zone = zoneRepository.findById(s.getZoneId()).orElseThrow();
        return toResponse(s, crop.getCropCode(), zone.getZoneCode());
    }

    /**
     * 下发：requestId 幂等；新策略生效与旧策略失效同事务；
     * 同分区并发仅一个成功；相邻变更间隔不得少于 30 分钟（恰满允许）。
     */
    public ActivationResponse activate(String strategyCode, String requestId) {
        ClimateStrategy s = mustGet(strategyCode);
        String fingerprint = Fingerprints.sha256(requestId, "ACTIVATE", s.getId(), s.getZoneId());
        IdempotencyService.ActionResult<ActivationResponse> result = idempotencyService.execute(
                IdempotentBizType.STRATEGY_ACTIVATE, requestId, fingerprint,
                () -> new IdempotencyService.ActionResult<>(200, doActivate(s, requestId)),
                ActivationResponse.class);
        return result.body();
    }

    @Transactional
    public ActivationResponse doActivate(ClimateStrategy s, String requestId) {
        ClimateStrategy locked = strategyRepository.findById(s.getId()).orElseThrow();
        Zone zone = zoneRepository.findById(locked.getZoneId())
                .orElseThrow(() -> ApiException.notFound("分区不存在"));
        if (locked.getStatus() == StrategyStatus.ACTIVE) {
            throw ApiException.conflict("策略已在生效中: " + locked.getStrategyCode());
        }
        if (locked.getStatus() != StrategyStatus.VALIDATED) {
            throw ApiException.unprocessable("仅校验通过的策略可下发，当前状态: " + locked.getStatus());
        }

        Instant now = Instant.now(clock);
        // 锁定分区生效指针，串行化同分区的并发下发。
        ZoneActiveStrategy active = strategyRepository.findActiveByZoneForUpdate(locked.getZoneId()).orElse(null);
        strategyRepository.findLastChangeEffectiveAt(locked.getZoneId()).ifPresent(lastAt -> {
            if (Duration.between(lastAt, now).compareTo(MIN_CHANGE_INTERVAL) < 0) {
                long waitSeconds = MIN_CHANGE_INTERVAL.minus(Duration.between(lastAt, now)).getSeconds();
                throw ApiException.unprocessable("距上次策略变更不足 30 分钟，还需等待 " + waitSeconds + " 秒");
            }
        });

        Long previousId = null;
        String previousCode = null;
        if (active == null) {
            try {
                strategyRepository.insertActive(locked.getZoneId(), locked.getId(), now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("同分区并发下发冲突，仅允许一个策略生效");
            }
        } else {
            previousId = active.getStrategyId();
            strategyRepository.replaceActive(locked.getZoneId(), locked.getId(), now);
        }

        if (previousId != null) {
            strategyRepository.updateStatus(previousId, StrategyStatus.SUPERSEDED, "已被新策略替代", now);
            ClimateStrategy previous = strategyRepository.findById(previousId).orElse(null);
            if (previous != null) {
                previousCode = previous.getStrategyCode();
            }
        }
        strategyRepository.updateStatus(locked.getId(), StrategyStatus.ACTIVE, "生效中", now);

        StrategyChangeLog log = new StrategyChangeLog();
        log.setZoneId(locked.getZoneId());
        log.setOldStrategyId(previousId);
        log.setNewStrategyId(locked.getId());
        log.setEffectiveAt(now);
        log.setRequestId(requestId);
        log.setCreatedAt(now);
        strategyRepository.insertChangeLog(log);

        return new ActivationResponse(locked.getStrategyCode(), zone.getZoneCode(), previousCode, now);
    }

    public StrategyResponse get(String strategyCode) {
        ClimateStrategy s = mustGet(strategyCode);
        Crop crop = cropRepository.findById(s.getCropId()).orElseThrow();
        Zone zone = zoneRepository.findById(s.getZoneId()).orElseThrow();
        return toResponse(s, crop.getCropCode(), zone.getZoneCode());
    }

    private void checkWindow(List<String> violations, String label,
                             BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            violations.add(label + "设定点 " + value + " 不在允许窗口 [" + min + ", " + max + "] 内");
        }
    }

    private ClimateStrategy mustGet(String strategyCode) {
        return strategyRepository.findByCode(strategyCode)
                .orElseThrow(() -> ApiException.notFound("策略不存在: " + strategyCode));
    }

    private StrategyResponse toResponse(ClimateStrategy s, String cropCode, String zoneCode) {
        return new StrategyResponse(
                s.getId(), s.getStrategyCode(), cropCode, zoneCode,
                s.getDayTempTarget(), s.getDayTempTolerance(),
                s.getDayHumidityTarget(), s.getDayHumidityTolerance(),
                s.getNightTempTarget(), s.getNightTempTolerance(),
                s.getNightHumidityTarget(), s.getNightHumidityTolerance(),
                s.getStatus().name(), s.getValidationMessage());
    }
}
