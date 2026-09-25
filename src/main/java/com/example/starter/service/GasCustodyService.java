package com.example.starter.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.ConflictException;
import com.example.starter.domain.NotFoundException;
import com.example.starter.domain.UnprocessableException;
import com.example.starter.domain.VoucherStatus;
import com.example.starter.domain.VoucherType;
import com.example.starter.persistence.DisputeRepository;
import com.example.starter.persistence.IdempotencyPO;
import com.example.starter.persistence.IdempotencyRepository;
import com.example.starter.persistence.MeterReadingPO;
import com.example.starter.persistence.NominationPO;
import com.example.starter.persistence.NominationRepository;
import com.example.starter.persistence.ReadingRepository;
import com.example.starter.persistence.StationRepository;
import com.example.starter.persistence.VoucherPO;
import com.example.starter.persistence.VoucherRepository;
import com.example.starter.web.dto.AdjustmentRequest;
import com.example.starter.web.dto.AssessmentResponse;
import com.example.starter.web.dto.ConfirmRequest;
import com.example.starter.web.dto.DisputeRequest;
import com.example.starter.web.dto.NominationRequest;
import com.example.starter.web.dto.NominationResponse;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingResponse;
import com.example.starter.web.dto.StationRequest;
import com.example.starter.web.dto.StationResponse;
import com.example.starter.web.dto.VoucherResponse;
import com.example.starter.web.dto.VoucherSubmitRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 天然气交接计量主服务：读数归位、凭证生成、双方确认、争议/封账、调整凭证与逐日偏差考核。
 *
 * <p>全部时间以 UTC 为准，气量日按 UTC 日界划分；可通过替换 {@link Clock} Bean 控制时间。
 */
@Service
public class GasCustodyService {

    /** 生效后的争议期长度：7 个自然日，截止 UTC 日期当日仍可发起争议。 */
    static final int DISPUTE_WINDOW_DAYS = 7;

    private final Clock clock;
    private final StationRepository stationRepository;
    private final NominationRepository nominationRepository;
    private final ReadingRepository readingRepository;
    private final VoucherRepository voucherRepository;
    private final DisputeRepository disputeRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public GasCustodyService(Clock applicationClock, StationRepository stationRepository,
            NominationRepository nominationRepository, ReadingRepository readingRepository,
            VoucherRepository voucherRepository, DisputeRepository disputeRepository,
            IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.clock = applicationClock;
        this.stationRepository = stationRepository;
        this.nominationRepository = nominationRepository;
        this.readingRepository = readingRepository;
        this.voucherRepository = voucherRepository;
        this.disputeRepository = disputeRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /** 服务操作输出：携带 HTTP 状态以便争议逾期等场景返回非 2xx 的稳定结果。 */
    public record Outcome<T>(int httpStatus, T body) {
    }

    /** 创建交接站（合成基础数据）。 */
    @Transactional
    public StationResponse createStation(StationRequest request) {
        Instant now = Instant.now(clock);
        var row = stationRepository.insert(request.stationCode(), request.stationName(),
                request.partyA(), request.partyB(), now);
        if (row.isEmpty()) {
            throw new ConflictException("交接站编码已存在: " + request.stationCode());
        }
        var created = row.get();
        return new StationResponse(created.code(), created.name(), created.partyA(),
                created.partyB(), created.createdAt());
    }

    /** 登记日指定气量与违约金单价。 */
    @Transactional
    public NominationResponse saveNomination(NominationRequest request) {
        requireStation(request.stationCode());
        nominationRepository.save(request.stationCode(), request.gasDay(),
                request.nominatedEnergyMj(), request.penaltyUnitPrice(), Instant.now(clock));
        return new NominationResponse(request.stationCode(), request.gasDay(),
                request.nominatedEnergyMj(), request.penaltyUnitPrice());
    }

    /**
     * 上报计量读数：按“交接站＋表底时刻”去重，乱序到达按时刻归位。
     * 若读数所属气量日的正常凭证已生成，则为迟到读数：标记并登记差异记录，不改动已生成凭证。
     */
    @Transactional
    public ReadingResponse uploadReading(ReadingRequest request) {
        requireStation(request.stationCode());
        Instant now = Instant.now(clock);
        var result = readingRepository.insertIfAbsent(request.stationCode(), request.meterId(),
                request.readingTime(), request.meterIndex(), request.heatingValue(), now);
        MeterReadingPO reading = result.reading();
        if (result.created()) {
            LocalDate gasDay = LocalDate.ofInstant(request.readingTime(), ZoneOffset.UTC);
            var existingVoucher = voucherRepository.findNormal(request.stationCode(), gasDay);
            if (existingVoucher.isPresent()) {
                readingRepository.markLateById(reading.id());
                readingRepository.insertDifference(existingVoucher.get().voucherNo(), reading.id(),
                        request.stationCode(), gasDay, reading,
                        "凭证 " + existingVoucher.get().voucherNo()
                                + " 生成后到达的读数，登记差异，不改原凭证", now);
                // 重新读取以反映迟到标记
                reading = readingRepository.findById(reading.id()).orElseThrow();
            }
        }
        return toReadingResponse(reading);
    }

    /** 查询某站某气量日读数（按表底时刻升序归位）。 */
    public List<ReadingResponse> listReadings(String stationCode, LocalDate gasDay) {
        return readingRepository.findByGasDay(stationCode, gasDay).stream()
                .map(this::toReadingResponse).toList();
    }

    /** 提交（生成）凭证：requestId 幂等，同站同气量日只允许一张正常凭证。 */
    @Transactional
    public Outcome<VoucherResponse> submitVoucher(VoucherSubmitRequest request) {
        String fingerprint = fingerprint("VOUCHER_SUBMIT", request.requestId(),
                request.stationCode(), request.gasDay());
        return withIdempotency(request.requestId(), "VOUCHER_SUBMIT", fingerprint,
                HttpStatus.CREATED.value(), () -> doSubmitVoucher(request),
                VoucherResponse.class);
    }

    private Outcome<VoucherResponse> doSubmitVoucher(VoucherSubmitRequest request) {
        requireStation(request.stationCode());
        if (voucherRepository.findNormal(request.stationCode(), request.gasDay()).isPresent()) {
            throw new ConflictException(
                    "同站同气量日只允许一张有效凭证: " + request.stationCode() + " "
                            + request.gasDay());
        }
        List<MeterReadingPO> readings = readingRepository.findByGasDay(request.stationCode(),
                request.gasDay());
        // 凭证以气量日 24:00（UTC）前已落库确认的读数为准；findByGasDay 取 [day 00:00, 次日 00:00)
        final BigDecimal volume;
        final BigDecimal avgHeatingValue;
        try {
            volume = EnergyCalculator.dayVolume(readings);
            avgHeatingValue = EnergyCalculator.weightedHeatingValue(readings);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException(e.getMessage());
        }
        if (volume.signum() < 0) {
            throw new UnprocessableException("交接体积不能为负，检查表底单调性");
        }
        BigDecimal energy = EnergyCalculator.energy(volume, avgHeatingValue);
        Instant now = Instant.now(clock);
        String voucherNo = "VC" + request.gasDay().toString().replace("-", "") + "-"
                + randomToken();
        VoucherPO po = new VoucherPO(null, voucherNo, request.requestId(),
                request.stationCode(), request.gasDay(), VoucherType.NORMAL.name(),
                volume, avgHeatingValue, energy, VoucherStatus.DRAFT.name(), false, false,
                null, null, null, null, null, null, null, null, null,
                fingerprint("VOUCHER_ROW", request.requestId(), request.stationCode(),
                        request.gasDay(), volume, avgHeatingValue, energy),
                now, now);
        try {
            voucherRepository.insert(po);
        } catch (DuplicateKeyException e) {
            // 交由幂等层统一处理：同键并发时等待重放首次结果；确属同日第二张凭证时转 409
            throw e;
        }
        return new Outcome<>(HttpStatus.CREATED.value(), toResponse(po));
    }

    /** 交接方确认；同一事务内双方先后确认一致即生效，单方不生效。 */
    @Transactional
    public Outcome<VoucherResponse> confirm(ConfirmRequest request) {
        String party = requireParty(request.party());
        String fingerprint = fingerprint("CONFIRM", request.requestId(), request.voucherNo(),
                party, request.confirmer());
        return withIdempotency(request.requestId(), "CONFIRM", fingerprint, HttpStatus.OK.value(),
                () -> doConfirm(request, party), VoucherResponse.class);
    }

    private Outcome<VoucherResponse> doConfirm(ConfirmRequest request, String party) {
        VoucherPO voucher = requireVoucherForUpdate(request.voucherNo());
        switch (VoucherStatus.valueOf(voucher.status())) {
            case SEALED, ADJUSTED -> throw new ConflictException("凭证已封账终态，确认被拒绝");
            case DISPUTED -> throw new ConflictException("凭证处于争议挂起中，不能确认");
            default -> { }
        }
        voucherRepository.updateConfirmation(voucher.voucherNo(), party, request.confirmer(),
                Instant.now(clock));
        VoucherPO updated = voucherRepository.findByNoForUpdate(voucher.voucherNo()).orElseThrow();
        if (updated.partyAConfirmed() && updated.partyBConfirmed()) {
            Instant effectiveAt = Instant.now(clock);
            LocalDate deadline = LocalDate.ofInstant(effectiveAt, ZoneOffset.UTC)
                    .plusDays(DISPUTE_WINDOW_DAYS);
            int rows = voucherRepository.markEffective(voucher.voucherNo(), effectiveAt,
                    deadline, effectiveAt);
            if (rows == 0) {
                throw new ConflictException("凭证状态已变化，双方确认未能生效");
            }
            updated = voucherRepository.findByNoForUpdate(voucher.voucherNo()).orElseThrow();
        }
        return new Outcome<>(HttpStatus.OK.value(), toResponse(updated));
    }

    /**
     * 发起争议：生效日起 7 个自然日内（UTC，恰第 7 日截止日）受理；逾期则在本事务内封账，
     * 返回 409 且凭证落为 SEALED 终态。与并发封账经条件更新竞争，只形成唯一结果。
     */
    @Transactional
    public Outcome<VoucherResponse> dispute(DisputeRequest request) {
        String party = requireParty(request.party());
        String fingerprint = fingerprint("DISPUTE", request.requestId(), request.voucherNo(),
                party, request.reason());
        return withIdempotency(request.requestId(), "DISPUTE", fingerprint, HttpStatus.OK.value(),
                () -> doDispute(request, party), VoucherResponse.class);
    }

    private Outcome<VoucherResponse> doDispute(DisputeRequest request, String party) {
        VoucherPO voucher = requireVoucherForUpdate(request.voucherNo());
        LocalDate today = LocalDate.now(clock);
        VoucherStatus status = VoucherStatus.valueOf(voucher.status());
        switch (status) {
            case SEALED, ADJUSTED -> throw new ConflictException("凭证已封账终态，争议被拒绝");
            case DISPUTED -> throw new ConflictException("凭证已处于争议挂起状态");
            case DRAFT -> throw new ConflictException("凭证尚未经双方确认生效，不能发起争议");
            case EFFECTIVE -> { }
        }
        if (voucher.disputeDeadline() == null) {
            throw new ConflictException("生效凭证缺少争议期截止日");
        }
        if (today.isAfter(voucher.disputeDeadline())) {
            Instant now = Instant.now(clock);
            int sealed = voucherRepository.seal(voucher.voucherNo(), now, now);
            if (sealed == 0) {
                throw new ConflictException("并发操作已形成唯一结果，请重新查询凭证状态");
            }
            VoucherPO sealedVoucher = voucherRepository.findByNoForUpdate(voucher.voucherNo())
                    .orElseThrow();
            // 不抛异常：封账须随本事务提交，同时以 409 表达“逾期争议被拒绝”
            return new Outcome<>(HttpStatus.CONFLICT.value(), toResponse(sealedVoucher));
        }
        Instant now = Instant.now(clock);
        int cas = voucherRepository.compareAndUpdateStatus(voucher.voucherNo(),
                VoucherStatus.EFFECTIVE.name(), VoucherStatus.DISPUTED.name(), now);
        if (cas == 0) {
            throw new ConflictException("并发封账/争议已形成唯一结果，请重新查询凭证状态");
        }
        try {
            disputeRepository.insert(voucher.voucherNo(), request.requestId(), party,
                    request.reason(), "OPEN", now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("争议 requestId 已被使用且参数不一致");
        }
        VoucherPO disputed = voucherRepository.findByNoForUpdate(voucher.voucherNo())
                .orElseThrow();
        return new Outcome<>(HttpStatus.OK.value(), toResponse(disputed));
    }

    /**
     * 封账：争议期满后为终态；重复封账幂等成功。争议期内或争议挂起中拒绝。
     * 与并发争议经 EFFECTIVE 行的条件更新竞争，只形成唯一结果。
     */
    @Transactional
    public Outcome<VoucherResponse> seal(String voucherNo, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return new Outcome<>(HttpStatus.OK.value(), doSeal(voucherNo));
        }
        String fingerprint = fingerprint("SEAL", requestId, voucherNo);
        return withIdempotency(requestId, "SEAL", fingerprint, HttpStatus.OK.value(),
                () -> new Outcome<>(HttpStatus.OK.value(), doSeal(voucherNo)),
                VoucherResponse.class);
    }

    private VoucherResponse doSeal(String voucherNo) {
        VoucherPO voucher = requireVoucherForUpdate(voucherNo);
        VoucherStatus status = VoucherStatus.valueOf(voucher.status());
        if (status == VoucherStatus.SEALED) {
            // 重复封账返回幂等成功
            return toResponse(voucher);
        }
        if (status == VoucherStatus.ADJUSTED) {
            throw new ConflictException("凭证已被调整凭证替代，终态只读");
        }
        if (status == VoucherStatus.DISPUTED) {
            throw new ConflictException("凭证争议挂起中，不能封账");
        }
        if (status == VoucherStatus.DRAFT) {
            throw new ConflictException("凭证尚未经双方确认生效，不能封账");
        }
        LocalDate today = LocalDate.now(clock);
        if (voucher.disputeDeadline() != null && !today.isAfter(voucher.disputeDeadline())) {
            throw new ConflictException("仍在 7 个自然日争议期内（截止 "
                    + voucher.disputeDeadline() + "），不能提前封账");
        }
        Instant now = Instant.now(clock);
        int rows = voucherRepository.seal(voucherNo, now, now);
        if (rows == 0) {
            // 并发争议抢先把凭证置为 DISPUTED：唯一结果归争议
            VoucherPO current = voucherRepository.findByNoForUpdate(voucherNo).orElseThrow();
            if (VoucherStatus.DISPUTED.name().equals(current.status())) {
                throw new ConflictException("并发争议已受理，封账未成功，唯一结果为争议挂起");
            }
            if (VoucherStatus.SEALED.name().equals(current.status())) {
                return toResponse(current);
            }
            throw new ConflictException("并发操作已形成唯一结果，请重新查询凭证状态");
        }
        return toResponse(voucherRepository.findByNoForUpdate(voucherNo).orElseThrow());
    }

    /**
     * 双方签认的差错调整凭证：仅允许对已封账原凭证处理，引用原凭证号，
     * 原凭证转入 ADJUSTED 终态，挂起争议一并闭环。
     */
    @Transactional
    public Outcome<VoucherResponse> adjustment(AdjustmentRequest request) {
        String fingerprint = fingerprint("ADJUSTMENT", request.requestId(),
                request.originalVoucherNo(), request.partyAConfirmer(),
                request.partyBConfirmer(), request.volume(), request.avgHeatingValue(),
                request.reason());
        return withIdempotency(request.requestId(), "ADJUSTMENT", fingerprint,
                HttpStatus.CREATED.value(), () -> doAdjustment(request),
                VoucherResponse.class);
    }

    private Outcome<VoucherResponse> doAdjustment(AdjustmentRequest request) {
        VoucherPO original = requireVoucherForUpdate(request.originalVoucherNo());
        VoucherStatus originalStatus = VoucherStatus.valueOf(original.status());
        // 封账后差错，或争议挂起后双方签认闭环，均以调整凭证处理
        if (originalStatus != VoucherStatus.SEALED
                && originalStatus != VoucherStatus.DISPUTED) {
            throw new ConflictException("仅封账后或争议挂起的凭证可经双方签认的调整凭证处理");
        }
        if (request.avgHeatingValue().signum() <= 0 || request.volume().signum() < 0) {
            throw new UnprocessableException("调整体积不能为负且加权热值必须为正");
        }
        if (voucherRepository.findByStationAndDay(original.stationCode(), original.gasDay())
                .stream().anyMatch(v -> VoucherType.ADJUSTMENT.name().equals(v.voucherType()))) {
            throw new ConflictException("该气量日调整凭证已存在，差错处理具有唯一性");
        }
        BigDecimal energy = EnergyCalculator.energy(request.volume(),
                request.avgHeatingValue());
        Instant now = Instant.now(clock);
        LocalDate deadline = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .plusDays(DISPUTE_WINDOW_DAYS);
        String adjustmentNo = "ADJ" + original.gasDay().toString().replace("-", "") + "-"
                + randomToken();
        VoucherPO adjustment = new VoucherPO(null, adjustmentNo, request.requestId(),
                original.stationCode(), original.gasDay(), VoucherType.ADJUSTMENT.name(),
                request.volume(), request.avgHeatingValue(), energy,
                VoucherStatus.EFFECTIVE.name(), true, true, request.partyAConfirmer(),
                request.partyBConfirmer(), now, now, now, deadline, null,
                original.voucherNo(), request.reason(), fingerprint("ADJUSTMENT_ROW",
                        request.requestId(), original.voucherNo(), request.volume(),
                        request.avgHeatingValue(), energy), now, now);
        try {
            voucherRepository.insert(adjustment);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("调整凭证或 requestId 冲突，未产生新版本");
        }
        int marked = voucherRepository.markAdjusted(original.voucherNo(), now);
        if (marked == 0) {
            throw new ConflictException("原凭证状态已变化，调整失败");
        }
        disputeRepository.resolveOpen(original.voucherNo(), adjustmentNo, now);
        return new Outcome<>(HttpStatus.CREATED.value(), toResponse(adjustment));
    }

    /** 查询凭证。 */
    public VoucherResponse getVoucher(String voucherNo) {
        return toResponse(voucherRepository.findByNo(voucherNo)
                .orElseThrow(() -> new NotFoundException("凭证不存在: " + voucherNo)));
    }

    /**
     * 逐日复算偏差考核：以该气量日最新有效凭证（若有调整凭证则以调整凭证为准）的实际能量
     * 对照日指定能量；|偏差| ≤ 指定×3%（含）免考，超出部分按单价计违约金。按气量日升序，结果稳定。
     */
    public List<AssessmentResponse> listAssessments(String stationCode, LocalDate from,
            LocalDate to) {
        if (from.isAfter(to)) {
            throw new UnprocessableException("起始气量日不能晚于截止气量日");
        }
        return java.util.stream.Stream.iterate(from, d -> !d.isAfter(to), d -> d.plusDays(1))
                .map(day -> assessDay(stationCode, day))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<AssessmentResponse> assessDay(String stationCode, LocalDate day) {
        Optional<NominationPO> nomination = nominationRepository.find(stationCode, day);
        if (nomination.isEmpty()) {
            return Optional.empty();
        }
        List<VoucherPO> vouchers = voucherRepository.findByStationAndDay(stationCode, day);
        VoucherPO basis = vouchers.stream()
                .filter(v -> !VoucherStatus.DRAFT.name().equals(v.status()))
                .reduce((first, second) -> second)
                .orElse(null);
        if (basis == null) {
            return Optional.empty();
        }
        NominationPO nom = nomination.get();
        BigDecimal actual = basis.energyMj();
        BigDecimal nominated = nom.nominatedEnergyMj();
        BigDecimal deviation = AssessmentCalculator.deviation(actual, nominated);
        boolean exempt = AssessmentCalculator.isExempt(actual, nominated);
        BigDecimal excess = AssessmentCalculator.billableExcess(actual, nominated);
        BigDecimal penalty = AssessmentCalculator.penaltyAmount(actual, nominated,
                nom.penaltyUnitPrice());
        return Optional.of(new AssessmentResponse(stationCode, day, basis.voucherNo(), actual,
                nominated, deviation, deviation.abs(),
                AssessmentCalculator.deviationRatio(actual, nominated), exempt, excess,
                nom.penaltyUnitPrice(), penalty));
    }

    // ---- 幂等与公共辅助 -------------------------------------------------

    /**
     * requestId 幂等执行：指纹覆盖 requestId 与全部业务字段。
     * 同键同参返回首次结果（含首次状态码与响应快照），同键异参 409 且不产生新版本。
     */
    private <T> Outcome<T> withIdempotency(String requestId, String operation, String fingerprint,
            int defaultStatus, Supplier<Outcome<T>> action, Class<T> bodyType) {
        Optional<IdempotencyPO> existing = idempotencyRepository.find(requestId);
        if (existing.isPresent()) {
            IdempotencyPO row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已存在但业务参数不一致，拒绝执行");
            }
            return new Outcome<>(row.httpStatus(), readBody(row.resultBody(), bodyType));
        }
        Outcome<T> outcome;
        try {
            outcome = action.get();
        } catch (DuplicateKeyException e) {
            // 并发同键：对方事务可能尚未提交，短暂等待后重放首次结果；
            // 确属同日凭证等其他唯一约束时转 409，不产生新版本
            IdempotencyPO winner = waitForIdempotency(requestId, 40, 50);
            if (winner != null) {
                if (!winner.fingerprint().equals(fingerprint)) {
                    throw new ConflictException("requestId 已存在但业务参数不一致，拒绝执行");
                }
                return new Outcome<>(winner.httpStatus(),
                        readBody(winner.resultBody(), bodyType));
            }
            throw new ConflictException("唯一约束冲突，未产生新版本: " + e.getMessage());
        }
        Instant now = Instant.now(clock);
        try {
            idempotencyRepository.insert(new IdempotencyPO(requestId, operation, fingerprint,
                    outcome.httpStatus(), writeBody(outcome.body()), now));
        } catch (DuplicateKeyException e) {
            // 并发同键请求：以先提交者为准重放或判异参
            IdempotencyPO winner = idempotencyRepository.find(requestId).orElseThrow();
            if (!winner.fingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已存在但业务参数不一致，拒绝执行");
            }
            return new Outcome<>(winner.httpStatus(), readBody(winner.resultBody(), bodyType));
        }
        return outcome;
    }

    private <T> T readBody(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应快照反序列化失败", e);
        }
    }

    /**
     * 并发同键时等待先到事务提交并可见；超时返回 null（调用方按其他唯一约束冲突处理）。
     */
    private IdempotencyPO waitForIdempotency(String requestId, int maxAttempts, long sleepMillis) {
        for (int i = 0; i < maxAttempts; i++) {
            Optional<IdempotencyPO> row = idempotencyRepository.find(requestId);
            if (row.isPresent()) {
                return row.get();
            }
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应快照序列化失败", e);
        }
    }

    private StationRepository.StationRow requireStation(String stationCode) {
        return stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new UnprocessableException("交接站不存在: " + stationCode));
    }

    private VoucherPO requireVoucherForUpdate(String voucherNo) {
        return voucherRepository.findByNoForUpdate(voucherNo)
                .orElseThrow(() -> new NotFoundException("凭证不存在: " + voucherNo));
    }

    private String requireParty(String party) {
        if (!"A".equals(party) && !"B".equals(party)) {
            throw new UnprocessableException("party 只允许 A（交出方）或 B（接收方）");
        }
        return party;
    }

    private ReadingResponse toReadingResponse(MeterReadingPO r) {
        return new ReadingResponse(r.id(), r.stationCode(), r.meterId(), r.readingTime(),
                r.meterIndex(), r.heatingValue(), r.receivedAt(), r.late());
    }

    private VoucherResponse toResponse(VoucherPO v) {
        return new VoucherResponse(v.voucherNo(), v.requestId(), v.stationCode(), v.gasDay(),
                v.voucherType(), v.volume(), v.avgHeatingValue(), v.energyMj(), v.status(),
                v.partyAConfirmed(), v.partyBConfirmed(), v.partyAName(), v.partyBName(),
                v.partyAAt(), v.partyBAt(), v.effectiveAt(), v.disputeDeadline(), v.sealedAt(),
                v.originalVoucherNo(), v.reason(), v.createdAt());
    }

    /** 对操作名、requestId 与全部业务字段做 SHA-256 指纹（null 以空串参与）。 */
    static String fingerprint(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    private String randomToken() {
        return HexFormat.of().formatHex(
                java.security.SecureRandom.getSeed(6)).toUpperCase();
    }
}
