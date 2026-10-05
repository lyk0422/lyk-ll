package com.example.starter.flood.service;

import com.example.starter.flood.domain.CapacityJudgment;
import com.example.starter.flood.domain.DangerJudgment;
import com.example.starter.flood.domain.IndicatorJudgment;
import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.IdempotentRepository;
import com.example.starter.flood.repo.IdempotentRepository.IdempotencyOutcome;
import com.example.starter.flood.repo.PondPO;
import com.example.starter.flood.repo.PondRepository;
import com.example.starter.flood.repo.RoutingPO;
import com.example.starter.flood.repo.RoutingRepository;
import com.example.starter.flood.repo.WarningPO;
import com.example.starter.flood.repo.WarningRepository;
import com.example.starter.flood.service.StorageCurveCalculator.CurvePoint;
import com.example.starter.flood.support.BusinessNoGenerator;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.RoutingRequest;
import com.example.starter.flood.web.dto.RoutingView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调洪演算服务：由合成库容曲线反查当前蓄水量与可用调洪库容，
 * 库容口径与干滩长度、浸润线埋深双指标口径合并为综合判定；
 * 输入快照、判定口径与全部业务字段进入幂等指纹，演算结果可复算。
 */
@Service
public class RoutingService {

    static final String CRITERION = "库容: 设计洪水量<可用调洪库容=安全, 恰等=临界, 大于=须泄流; "
            + "干滩长度/浸润线埋深: 实测>安全下限=安全, 恰等=临界, 小于=险; "
            + "综合: 任一指标险=险; 否则库容须泄流=须泄流; 否则任一临界=临界; 全安全=安全";

    private final RoutingRepository routingRepository;
    private final PondRepository pondRepository;
    private final WarningRepository warningRepository;
    private final WarningService warningService;
    private final IdempotentRepository idempotentRepository;
    private final StorageCurveCalculator curveCalculator;
    private final BusinessNoGenerator noGenerator;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public RoutingService(RoutingRepository routingRepository, PondRepository pondRepository,
                          WarningRepository warningRepository,
                          WarningService warningService,
                          IdempotentRepository idempotentRepository,
                          StorageCurveCalculator curveCalculator,
                          BusinessNoGenerator noGenerator, JsonSupport jsonSupport,
                          Clock floodClock) {
        this.routingRepository = routingRepository;
        this.pondRepository = pondRepository;
        this.warningRepository = warningRepository;
        this.warningService = warningService;
        this.idempotentRepository = idempotentRepository;
        this.curveCalculator = curveCalculator;
        this.noGenerator = noGenerator;
        this.jsonSupport = jsonSupport;
        this.clock = floodClock;
    }

    /**
     * 执行调洪演算（requestId 幂等）。
     */
    @Transactional
    public RoutingView route(RoutingRequest request) {
        PondPO pond = pondRepository.requireByCode(request.pondCode());
        WarningPO warning = warningService.requireByNo(request.warningNo());
        warningService.requireActive(warning);

        // 库容反查与判定是纯计算，先于幂等登记完成：越界/非法输入失败不残留幂等键
        List<CurvePoint> points = pondRepository.findCurvePoints(pond.id);
        BigDecimal currentStorage = curveCalculator.storageAt(points, request.currentWaterLevel());
        BigDecimal maxStorage = pondRepository.maxStorage(pond.id);
        BigDecimal availableCapacity = maxStorage.subtract(currentStorage);

        CapacityJudgment capacityJudgment =
                RoutingJudgment.capacity(request.designFloodVolume(), availableCapacity);
        IndicatorJudgment dryBeach =
                RoutingJudgment.indicator(request.dryBeachLength(), pond.dryBeachLimit);
        IndicatorJudgment phreatic =
                RoutingJudgment.indicator(request.phreaticDepth(), pond.phreaticLimit);
        DangerJudgment danger = RoutingJudgment.combine(capacityJudgment, dryBeach, phreatic);

        String fingerprint = jsonSupport.fingerprint(fingerprintFields(request));
        LocalDateTime now = LocalDateTime.now(clock);
        IdempotencyOutcome outcome =
                idempotentRepository.register(request.requestId(), "ROUTING", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的调洪演算");
            }
            RoutingPO existing = routingRepository.findByRequestId(request.requestId());
            return toView(existing, pond.pondCode, true);
        }

        RoutingPO po = new RoutingPO();
        po.routingNo = noGenerator.next("YS");
        po.pondId = pond.id;
        po.warningId = warning.id;
        po.requestId = request.requestId();
        po.currentWaterLevel = request.currentWaterLevel();
        po.currentStorage = currentStorage;
        po.maxStorage = maxStorage;
        po.availableCapacity = availableCapacity;
        po.designFloodVolume = request.designFloodVolume();
        po.capacityJudgment = capacityJudgment.name();
        po.dryBeachLength = request.dryBeachLength();
        po.phreaticDepth = request.phreaticDepth();
        po.dryBeachJudgment = dryBeach.name();
        po.phreaticJudgment = phreatic.name();
        po.dangerJudgment = danger.name();
        // 版本号按处置链递增：静态演算同样占用一个版本，滚动重算在其上继续累加
        RoutingPO latest = routingRepository.findLatestByWarningId(warning.id);
        po.version = latest == null ? 1 : latest.version + 1;
        po.triggerType = "MANUAL";
        po.inflowHydrograph = null;
        po.prevRoutingId = null;
        po.inputSnapshot = jsonSupport.toJson(snapshot(request, currentStorage, maxStorage,
                availableCapacity));
        po.criterion = CRITERION;
        po.createdAt = now;
        routingRepository.insert(po);
        idempotentRepository.bindRef(request.requestId(),
                routingRepository.findByRequestId(request.requestId()).id);
        return toView(po, pond.pondCode, false);
    }

    /**
     * 按编号查询演算结果，不存在抛 404。
     */
    public RoutingPO requireByNo(String routingNo) {
        RoutingPO routing = routingRepository.findByNo(routingNo);
        if (routing == null) {
            throw FloodControlException.notFound("调洪演算不存在: " + routingNo);
        }
        return routing;
    }

    private Map<String, Object> fingerprintFields(RoutingRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "ROUTING");
        fields.put("pondCode", request.pondCode());
        fields.put("warningNo", request.warningNo());
        fields.put("currentWaterLevel", request.currentWaterLevel().stripTrailingZeros().toPlainString());
        fields.put("designFloodVolume", request.designFloodVolume().stripTrailingZeros().toPlainString());
        fields.put("dryBeachLength", request.dryBeachLength() == null
                ? null : request.dryBeachLength().stripTrailingZeros().toPlainString());
        fields.put("phreaticDepth", request.phreaticDepth() == null
                ? null : request.phreaticDepth().stripTrailingZeros().toPlainString());
        return fields;
    }

    private Map<String, Object> snapshot(RoutingRequest request, BigDecimal currentStorage,
                                         BigDecimal maxStorage, BigDecimal availableCapacity) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.putAll(fingerprintFields(request));
        snap.put("currentStorage", currentStorage);
        snap.put("maxStorage", maxStorage);
        snap.put("availableCapacity", availableCapacity);
        return snap;
    }

    private RoutingView toView(RoutingPO po, String pondCode, boolean replay) {
        return new RoutingView(po.routingNo, warningNo(po.warningId), pondCode,
                po.currentWaterLevel, po.currentStorage, po.maxStorage, po.availableCapacity,
                po.designFloodVolume, po.capacityJudgment, po.dryBeachLength, po.phreaticDepth,
                po.dryBeachJudgment, po.phreaticJudgment, po.dangerJudgment, po.inputSnapshot,
                po.criterion, replay, po.createdAt);
    }

    private String warningNo(Long warningId) {
        if (warningId == null) {
            return null;
        }
        WarningPO warning = warningRepository.findById(warningId);
        return warning == null ? null : warning.warningNo;
    }
}
