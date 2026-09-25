package com.example.starter.tailings.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.tailings.domain.Verdict;
import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.idempotency.IdempotencyService;
import com.example.starter.tailings.repo.CalculationRepository;
import com.example.starter.tailings.repo.PondRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 调洪演算：由当前库水位按合成库容曲线反查可用调洪库容，
 * 库容口径与安全口径（干滩长度、浸润线埋深）双口径取最重。
 * 演算以 requestId 幂等，输入快照与判定口径落库，可复算。
 */
@Service
public class FloodRoutingService {

    private final PondRepository pondRepository;
    private final CalculationRepository calculationRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public FloodRoutingService(PondRepository pondRepository, CalculationRepository calculationRepository,
                               IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.pondRepository = pondRepository;
        this.calculationRepository = calculationRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 调洪演算入参。
     *
     * @param requestId         幂等请求ID
     * @param pondId            尾矿库ID
     * @param currentLevel      当前库水位(米)
     * @param designFloodVolume 设计洪水量(立方米)
     * @param dryBeachLength    干滩长度(米)
     * @param phreaticDepth     浸润线埋深(米)
     */
    public record CalcRequest(String requestId, long pondId, BigDecimal currentLevel,
                              BigDecimal designFloodVolume, BigDecimal dryBeachLength,
                              BigDecimal phreaticDepth) {
    }

    /**
     * 调洪演算结果。
     */
    public record CalcResult(long calcId, String requestId, long pondId, BigDecimal availableCapacity,
                             String capacityVerdict, String safetyVerdict, String verdict,
                             LocalDateTime createdAt) {
    }

    /**
     * 复算核对结果。
     */
    public record RecheckResult(long calcId, String storedVerdict, String recomputedVerdict,
                                boolean consistent) {
    }

    @Transactional
    public CalcResult calculate(CalcRequest request) {
        String canonical = "pondId=" + request.pondId()
                + "|currentLevel=" + request.currentLevel().toPlainString()
                + "|designFloodVolume=" + request.designFloodVolume().toPlainString()
                + "|dryBeachLength=" + request.dryBeachLength().toPlainString()
                + "|phreaticDepth=" + request.phreaticDepth().toPlainString();
        return idempotencyService.execute(request.requestId(), canonical, CalcResult.class,
                () -> doCalculate(request));
    }

    @Transactional(readOnly = true)
    public CalculationRepository.CalcRow get(long calcId) {
        return calculationRepository.findById(calcId)
                .orElseThrow(() -> BusinessException.notFound("调洪演算不存在: " + calcId));
    }

    /**
     * 按落库快照复算判定，核对与存储结论是否一致。
     */
    @Transactional(readOnly = true)
    public RecheckResult recheck(long calcId) {
        CalculationRepository.CalcRow row = get(calcId);
        PondRepository.PondRow pond = pondRepository.findPond(row.pondId())
                .orElseThrow(() -> BusinessException.notFound("尾矿库不存在: " + row.pondId()));
        List<PondRepository.CurvePoint> curve = pondRepository.curve(row.pondId());
        BigDecimal available = availableCapacity(curve, pond.maxFloodLevel(), row.currentLevel());
        Verdict recomputed = judge(available, row.designFloodVolume(),
                row.dryBeachLength(), row.phreaticDepth(), pond).verdict();
        return new RecheckResult(calcId, row.verdict(), recomputed.name(),
                recomputed.name().equals(row.verdict()));
    }

    private CalcResult doCalculate(CalcRequest request) {
        PondRepository.PondRow pond = pondRepository.findPond(request.pondId())
                .orElseThrow(() -> BusinessException.notFound("尾矿库不存在: " + request.pondId()));
        List<PondRepository.CurvePoint> curve = pondRepository.curve(request.pondId());
        if (curve.isEmpty()) {
            throw BusinessException.badRequest("库容曲线未配置: " + request.pondId());
        }
        BigDecimal available = availableCapacity(curve, pond.maxFloodLevel(), request.currentLevel());
        Judgement judgement = judge(available, request.designFloodVolume(),
                request.dryBeachLength(), request.phreaticDepth(), pond);
        String snapshot = buildSnapshot(request, pond, curve, available, judgement);
        long calcId = calculationRepository.insert(request.requestId(), request.pondId(),
                request.currentLevel(), request.designFloodVolume(), available,
                request.dryBeachLength(), request.phreaticDepth(),
                judgement.capacityVerdict().name(), judgement.safetyVerdict().name(),
                judgement.verdict().name(), snapshot);
        CalculationRepository.CalcRow row = calculationRepository.findById(calcId).orElseThrow();
        return new CalcResult(row.id(), row.requestId(), row.pondId(), row.availableCapacity(),
                row.capacityVerdict(), row.safetyVerdict(), row.verdict(), row.createdAt());
    }

    /**
     * 判定口径（两口径取最重）：
     * 库容口径——设计洪水量恰等可用库容判临界，超出判须泄流，不足判安全；
     * 安全口径——干滩长度或浸润线埋深任一低于安全下限判险，恰等下限判临界。
     */
    private Judgement judge(BigDecimal available, BigDecimal designFloodVolume,
                            BigDecimal dryBeachLength, BigDecimal phreaticDepth,
                            PondRepository.PondRow pond) {
        int cmp = designFloodVolume.compareTo(available);
        Verdict capacityVerdict = cmp > 0 ? Verdict.MUST_DISCHARGE
                : cmp == 0 ? Verdict.CRITICAL : Verdict.SAFE;
        boolean belowMin = dryBeachLength.compareTo(pond.dryBeachMin()) < 0
                || phreaticDepth.compareTo(pond.phreaticLineMin()) < 0;
        boolean atMin = dryBeachLength.compareTo(pond.dryBeachMin()) == 0
                || phreaticDepth.compareTo(pond.phreaticLineMin()) == 0;
        Verdict safetyVerdict = belowMin ? Verdict.DANGER : atMin ? Verdict.CRITICAL : Verdict.SAFE;
        return new Judgement(capacityVerdict, safetyVerdict, Verdict.worst(capacityVerdict, safetyVerdict));
    }

    private record Judgement(Verdict capacityVerdict, Verdict safetyVerdict, Verdict verdict) {
    }

    /**
     * 可用调洪库容 = 允许最高调洪水位对应库容 − 当前水位对应库容（线性插值，负值截断为0）。
     */
    static BigDecimal availableCapacity(List<PondRepository.CurvePoint> curve,
                                        BigDecimal maxFloodLevel, BigDecimal currentLevel) {
        BigDecimal atMax = interpolate(curve, maxFloodLevel);
        BigDecimal atCurrent = interpolate(curve, currentLevel);
        BigDecimal available = atMax.subtract(atCurrent).setScale(3, RoundingMode.HALF_UP);
        return available.signum() < 0 ? BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP) : available;
    }

    /**
     * 库容曲线线性插值；低于首点取首点库容，高于末点取末点库容。
     */
    static BigDecimal interpolate(List<PondRepository.CurvePoint> curve, BigDecimal level) {
        PondRepository.CurvePoint first = curve.get(0);
        PondRepository.CurvePoint last = curve.get(curve.size() - 1);
        if (level.compareTo(first.waterLevel()) <= 0) {
            return first.capacity();
        }
        if (level.compareTo(last.waterLevel()) >= 0) {
            return last.capacity();
        }
        for (int i = 0; i < curve.size() - 1; i++) {
            PondRepository.CurvePoint lo = curve.get(i);
            PondRepository.CurvePoint hi = curve.get(i + 1);
            if (level.compareTo(lo.waterLevel()) >= 0 && level.compareTo(hi.waterLevel()) <= 0) {
                BigDecimal ratio = level.subtract(lo.waterLevel())
                        .divide(hi.waterLevel().subtract(lo.waterLevel()), 10, RoundingMode.HALF_UP);
                return lo.capacity().add(hi.capacity().subtract(lo.capacity()).multiply(ratio))
                        .setScale(3, RoundingMode.HALF_UP);
            }
        }
        return last.capacity();
    }

    private String buildSnapshot(CalcRequest request, PondRepository.PondRow pond,
                                 List<PondRepository.CurvePoint> curve, BigDecimal available,
                                 Judgement judgement) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode inputs = root.putObject("inputs");
        inputs.put("pondId", request.pondId());
        inputs.put("currentLevel", request.currentLevel());
        inputs.put("designFloodVolume", request.designFloodVolume());
        inputs.put("dryBeachLength", request.dryBeachLength());
        inputs.put("phreaticDepth", request.phreaticDepth());
        ObjectNode thresholds = root.putObject("thresholds");
        thresholds.put("maxFloodLevel", pond.maxFloodLevel());
        thresholds.put("dryBeachMin", pond.dryBeachMin());
        thresholds.put("phreaticLineMin", pond.phreaticLineMin());
        var curveNode = root.putArray("storageCurve");
        for (PondRepository.CurvePoint point : curve) {
            ObjectNode node = curveNode.addObject();
            node.put("waterLevel", point.waterLevel());
            node.put("capacity", point.capacity());
        }
        root.put("availableCapacity", available);
        root.put("rule", "capacity: flood>available=>MUST_DISCHARGE, ==>CRITICAL, <=>SAFE;"
                + " safety: dryBeach<min||phreatic<min=>DANGER, ==min=>CRITICAL; verdict=worst");
        ObjectNode result = root.putObject("result");
        result.put("capacityVerdict", judgement.capacityVerdict().name());
        result.put("safetyVerdict", judgement.safetyVerdict().name());
        result.put("verdict", judgement.verdict().name());
        return root.toString();
    }

    /**
     * 幂等指纹规范化串拼接工具（供其他服务复用）。
     */
    static String canonical(Object... parts) {
        StringJoiner joiner = new StringJoiner("|");
        for (Object part : parts) {
            joiner.add(part instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(part));
        }
        return joiner.toString();
    }
}
