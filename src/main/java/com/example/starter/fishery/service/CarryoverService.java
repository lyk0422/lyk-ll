package com.example.starter.fishery.service;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.NotFoundException;
import com.example.starter.fishery.api.dto.CarryoverRequest;
import com.example.starter.fishery.api.dto.CarryoverResponse;
import com.example.starter.fishery.api.dto.CarryoverView;
import com.example.starter.fishery.db.CarryoverRepository;
import com.example.starter.fishery.db.CatalogRepository;
import com.example.starter.fishery.db.IdempotencyRepository;
import com.example.starter.fishery.db.IdempotencyRepository.AcquireResult;
import com.example.starter.fishery.db.QuotaRepository;
import com.example.starter.fishery.db.QuotaRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 年度结转应用服务。
 *
 * <p>关键口径：年度末未用配额按不超过上年配额 20% 结转至次年（恰 20% 合法）；
 * 已触发产地预警（核销用量达配额 90%）的额度不得结转；结转与次年额度初始化
 * 在同一事务内原子完成；结转额度在次年台账单列并优先核销；按 requestId 幂等，
 * 指纹覆盖 requestId 与全部业务字段，同键同参重放首次结果，异参 409。
 */
@Service
public class CarryoverService {

    /** 结转上限比例（含边界，恰 20% 合法）。 */
    static final BigDecimal CARRYOVER_CAP_RATIO = new BigDecimal("0.20");

    private static final String OP_CARRYOVER = "CARRYOVER";

    private final CarryoverRepository carryoverRepo;
    private final QuotaRepository quotaRepo;
    private final CatalogRepository catalogRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CarryoverService(CarryoverRepository carryoverRepo, QuotaRepository quotaRepo,
                            CatalogRepository catalogRepo, IdempotencyRepository idempotencyRepo,
                            ObjectMapper objectMapper, Clock clock) {
        this.carryoverRepo = carryoverRepo;
        this.quotaRepo = quotaRepo;
        this.catalogRepo = catalogRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 年度结转
    // ------------------------------------------------------------------

    @Transactional
    public CarryoverResponse carryover(CarryoverRequest request) {
        String fingerprint = JsonSupport.fingerprint(objectMapper, Map.of(
                "op", OP_CARRYOVER,
                "requestId", request.requestId(),
                "vesselCode", request.vesselCode(),
                "speciesCode", request.speciesCode(),
                "sourceYear", request.sourceYear().toString()));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_CARRYOVER, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return JsonSupport.replay(objectMapper, acquired.responseJson(), CarryoverResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        if (catalogRepo.findVessel(request.vesselCode()) == null) {
            throw new NotFoundException("渔船 " + request.vesselCode() + " 不存在");
        }
        if (catalogRepo.findSpecies(request.speciesCode()) == null) {
            throw new NotFoundException("鱼种 " + request.speciesCode() + " 不存在");
        }

        int sourceYear = request.sourceYear();
        int targetYear = sourceYear + 1;
        QuotaRow source = quotaRepo.findForUpdate(request.vesselCode(), request.speciesCode(), sourceYear)
                .orElseThrow(() -> new BusinessRuleException("渔船 " + request.vesselCode() + " 鱼种 "
                        + request.speciesCode() + " " + sourceYear + " 年度无核定配额，不能结转"));

        // 已触发产地预警的额度不得结转。
        if (quotaRepo.originAlertExists(request.vesselCode(), request.speciesCode(), sourceYear)) {
            throw new BusinessRuleException("渔船 " + request.vesselCode() + " 鱼种 "
                    + request.speciesCode() + " " + sourceYear + " 年度已触发产地预警，额度不得结转");
        }

        BigDecimal unused = source.quotaKg().subtract(source.usedKg());
        if (unused.signum() <= 0) {
            throw new BusinessRuleException("渔船 " + request.vesselCode() + " 鱼种 "
                    + request.speciesCode() + " " + sourceYear + " 年度无未用配额可结转");
        }
        // 结转上限为上年配额 20%（恰 20% 合法），实际结转取未用与上限较小者。
        BigDecimal cap = source.quotaKg().multiply(CARRYOVER_CAP_RATIO);
        BigDecimal carried = unused.min(cap);

        if (carryoverRepo.find(request.vesselCode(), request.speciesCode(), targetYear).isPresent()) {
            throw new ConflictException("渔船 " + request.vesselCode() + " 鱼种 " + request.speciesCode()
                    + " " + targetYear + " 年度已存在结转台账，不得重复结转");
        }

        // 结转与次年额度初始化在同一事务内原子完成；结转额度次年台账单列。
        quotaRepo.insertIfAbsent(request.vesselCode(), request.speciesCode(), targetYear);
        carryoverRepo.insert(request.vesselCode(), request.speciesCode(), targetYear,
                sourceYear, carried, Instant.now(clock));

        CarryoverResponse response = new CarryoverResponse(request.vesselCode(), request.speciesCode(),
                sourceYear, targetYear, unused, cap, carried, false);
        idempotencyRepo.saveResponse(request.requestId(), JsonSupport.toJson(objectMapper, response));
        return response;
    }

    // ------------------------------------------------------------------
    // 结转台账查询（次年台账单列）
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CarryoverView> list(String vesselCode, Integer year) {
        if (vesselCode == null || year == null) {
            return List.of();
        }
        return carryoverRepo.findByVesselYear(vesselCode, year).stream()
                .map(c -> new CarryoverView(c.vesselCode(), c.speciesCode(), c.quotaYear(),
                        c.sourceYear(), c.carriedKg(), c.usedKg(), c.remainingKg()))
                .toList();
    }
}
