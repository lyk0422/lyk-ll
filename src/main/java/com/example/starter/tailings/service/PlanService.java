package com.example.starter.tailings.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.tailings.domain.PlanStatus;
import com.example.starter.tailings.domain.Verdict;
import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.idempotency.IdempotencyService;
import com.example.starter.tailings.repo.CalculationRepository;
import com.example.starter.tailings.repo.PlanRepository;
import com.example.starter.tailings.repo.PondRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 泄流方案：孔口组合与泄量，总泄量不得超过下游安全泄量（恰等合法）。
 * 同一尾矿库通过 pond_active_plan 主键守卫保证唯一生效方案，
 * 险情报送与泄流方案并发时形成唯一处置链。
 */
@Service
public class PlanService {

    private final PlanRepository planRepository;
    private final CalculationRepository calculationRepository;
    private final PondRepository pondRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PlanService(PlanRepository planRepository, CalculationRepository calculationRepository,
                       PondRepository pondRepository, IdempotencyService idempotencyService,
                       ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.calculationRepository = calculationRepository;
        this.pondRepository = pondRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 孔口入参。
     *
     * @param name      孔口名称
     * @param discharge 该孔口泄量(立方米/秒)
     */
    public record OrificeInput(String name, BigDecimal discharge) {
    }

    /**
     * 泄流方案入参。
     */
    public record PlanRequest(String requestId, long calcId, List<OrificeInput> orifices) {
    }

    /**
     * 泄流方案结果。
     */
    public record PlanResult(long planId, String requestId, long calcId, long pondId,
                             BigDecimal totalDischarge, String status) {
    }

    @Transactional
    public PlanResult create(PlanRequest request) {
        if (request.orifices() == null || request.orifices().isEmpty()) {
            throw BusinessException.badRequest("孔口组合不能为空");
        }
        BigDecimal total = request.orifices().stream()
                .map(OrificeInput::discharge)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String canonical = FloodRoutingService.canonical(
                "calcId=" + request.calcId(), "orifices=" + request.orifices(), "total=" + total);
        return idempotencyService.execute(request.requestId(), canonical, PlanResult.class,
                () -> doCreate(request, total));
    }

    private PlanResult doCreate(PlanRequest request, BigDecimal total) {
        CalculationRepository.CalcRow calc = calculationRepository.findById(request.calcId())
                .orElseThrow(() -> BusinessException.notFound("调洪演算不存在: " + request.calcId()));
        Verdict verdict = Verdict.valueOf(calc.verdict());
        if (verdict != Verdict.MUST_DISCHARGE && verdict != Verdict.DANGER) {
            throw BusinessException.badRequest("当前判定为 " + calc.verdict() + "，无需泄流，不能生成泄流方案");
        }
        PondRepository.PondRow pond = pondRepository.findPond(calc.pondId())
                .orElseThrow(() -> BusinessException.notFound("尾矿库不存在: " + calc.pondId()));
        if (total.signum() <= 0) {
            throw BusinessException.badRequest("总泄量必须大于0");
        }
        if (total.compareTo(pond.downstreamSafeDischarge()) > 0) {
            throw BusinessException.badRequest("总泄量 " + total.toPlainString()
                    + " 超过下游安全泄量 " + pond.downstreamSafeDischarge().toPlainString());
        }
        String orificesJson = writeOrifices(request.orifices());
        long planId = planRepository.insertPlan(request.requestId(), calc.id(), calc.pondId(),
                orificesJson, total, PlanStatus.EFFECTIVE.name());
        try {
            planRepository.insertActiveGuard(calc.pondId(), planId);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("该尾矿库已存在生效中的泄流方案，处置链唯一");
        }
        return new PlanResult(planId, request.requestId(), calc.id(), calc.pondId(),
                total, PlanStatus.EFFECTIVE.name());
    }

    private String writeOrifices(List<OrificeInput> orifices) {
        try {
            return objectMapper.writeValueAsString(orifices);
        } catch (Exception e) {
            throw new IllegalStateException("孔口组合序列化失败", e);
        }
    }
}
