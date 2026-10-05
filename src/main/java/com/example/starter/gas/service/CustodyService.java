package com.example.starter.gas.service;

import com.example.starter.gas.ApiException;
import com.example.starter.gas.domain.AssessmentRow;
import com.example.starter.gas.domain.MeterReadingRow;
import com.example.starter.gas.domain.VoucherRow;
import com.example.starter.gas.dto.AdjustmentRequest;
import com.example.starter.gas.dto.AssessmentResponse;
import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.DisputeRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.ReadingSubmitResponse;
import com.example.starter.gas.dto.SealRequest;
import com.example.starter.gas.dto.VoucherCreateRequest;
import com.example.starter.gas.dto.VoucherResponse;
import com.example.starter.gas.repo.AssessmentRepository;
import com.example.starter.gas.repo.IdempotencyRepository;
import com.example.starter.gas.repo.MeterReadingRepository;
import com.example.starter.gas.repo.QualityRepository;
import com.example.starter.gas.repo.ReferenceDataRepository;
import com.example.starter.gas.repo.VoucherRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 天然气计量交接凭证与偏差考核主服务。
 *
 * <p>所有写操作以 requestId 幂等：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409 且不产生新版本。</p>
 */
@Service
public class CustodyService {

    private static final Logger log = LoggerFactory.getLogger(CustodyService.class);

    /** 争议期长度：生效后 7 个自然日（UTC），恰第 7 日仍可发起争议。 */
    static final int DISPUTE_WINDOW_DAYS = 7;

    private static final String TYPE_READING = "READING";
    private static final String TYPE_VOUCHER = "VOUCHER";
    private static final String TYPE_CONFIRM = "CONFIRM";
    private static final String TYPE_DISPUTE = "DISPUTE";
    private static final String TYPE_SEAL = "SEAL";
    private static final String TYPE_ADJUSTMENT = "ADJUSTMENT";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ReferenceDataRepository referenceDataRepository;
    private final MeterReadingRepository meterReadingRepository;
    private final VoucherRepository voucherRepository;
    private final AssessmentRepository assessmentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final QualityRepository qualityRepository;
    private final TerminalSealer terminalSealer;

    public CustodyService(Clock clock,
                          ObjectMapper objectMapper,
                          ReferenceDataRepository referenceDataRepository,
                          MeterReadingRepository meterReadingRepository,
                          VoucherRepository voucherRepository,
                          AssessmentRepository assessmentRepository,
                          IdempotencyRepository idempotencyRepository,
                          QualityRepository qualityRepository,
                          TerminalSealer terminalSealer) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.referenceDataRepository = referenceDataRepository;
        this.meterReadingRepository = meterReadingRepository;
        this.voucherRepository = voucherRepository;
        this.assessmentRepository = assessmentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.qualityRepository = qualityRepository;
        this.terminalSealer = terminalSealer;
    }

    /**
     * 提交计量读数：按“站＋表底时刻”去重，乱序按 UTC 日界归位；
     * 若该站日凭证已生成，后到读数记差异记录，不改已生成凭证。
     */
    @Transactional
    public ReadingSubmitResponse submitReading(ReadingSubmitRequest request) {
        String fingerprint = canonical(TYPE_READING, request.requestId(), request.stationCode(),
                request.meterTime(), request.volume().stripTrailingZeros().toPlainString(),
                request.heatingValue().stripTrailingZeros().toPlainString());
        return withIdempotency(TYPE_READING, request.requestId(), fingerprint,
                ReadingSubmitResponse.class, () -> doSubmitReading(request));
    }

    private ReadingSubmitResponse doSubmitReading(ReadingSubmitRequest request) {
        requireStation(request.stationCode());
        LocalDate gasDate = LocalDate.ofInstant(request.meterTime(), ZoneOffset.UTC);

        MeterReadingRow existing = meterReadingRepository.findByStationAndMeterTime(
                request.stationCode(), request.meterTime());
        if (existing != null) {
            // 同站同表底时刻天然同一读数：去重返回，不产生新版本
            return new ReadingSubmitResponse(existing.requestId(), existing.id(), existing.stationCode(),
                    existing.meterTime(), existing.gasDate(), existing.volume(),
                    existing.heatingValue(), true);
        }

        long id;
        try {
            id = meterReadingRepository.insert(request.requestId(), request.stationCode(),
                    request.meterTime(), gasDate, request.volume(), request.heatingValue(),
                    clock.instant());
        } catch (DuplicateKeyException ex) {
            // 并发提交同表底时刻：重查去重
            MeterReadingRow raced = meterReadingRepository.findByStationAndMeterTime(
                    request.stationCode(), request.meterTime());
            if (raced != null) {
                return new ReadingSubmitResponse(raced.requestId(), raced.id(), raced.stationCode(),
                        raced.meterTime(), raced.gasDate(), raced.volume(),
                        raced.heatingValue(), true);
            }
            throw ApiException.conflict("READING_DUPLICATED", "读数已存在且内容不一致");
        }

        // 凭证已先于本读数生成：迟到读数只入差异记录，不改凭证
        VoucherRow voucher = voucherRepository.findOriginalByStationAndDate(
                request.stationCode(), gasDate);
        if (voucher != null) {
            voucherRepository.insertReadingDiff(voucher.voucherNo(), id, request.stationCode(),
                    gasDate, request.volume(), request.heatingValue());
        }

        return new ReadingSubmitResponse(request.requestId(), id, request.stationCode(),
                request.meterTime(), gasDate, request.volume(), request.heatingValue(), false);
    }

    /**
     * 按气量日生成交接凭证：仅采用气量日 24:00（UTC）前已确认入库的读数；
     * 同站同气量日只允许一张有效原始凭证。
     */
    @Transactional
    public VoucherResponse createVoucher(VoucherCreateRequest request) {
        String fingerprint = canonical(TYPE_VOUCHER, request.requestId(),
                request.stationCode(), request.gasDate().toString());
        return withIdempotency(TYPE_VOUCHER, request.requestId(), fingerprint,
                VoucherResponse.class, () -> doCreateVoucher(request));
    }

    private VoucherResponse doCreateVoucher(VoucherCreateRequest request) {
        requireStation(request.stationCode());
        LocalDate gasDate = request.gasDate();

        String occupied = voucherRepository.findOccupiedVoucherNo(request.stationCode(), gasDate);
        if (occupied != null) {
            throw ApiException.conflict("VOUCHER_ALREADY_EXISTS",
                    "同站同气量日已存在有效凭证: " + occupied);
        }

        java.time.Instant now = clock.instant();
        java.time.Instant dayEnd = gasDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        if (now.isBefore(dayEnd)) {
            throw ApiException.badRequest("GAS_DAY_NOT_CLOSED",
                    "气量日 " + gasDate + " 的 24:00（UTC）尚未到，不能生成凭证");
        }

        List<MeterReadingRow> readings = meterReadingRepository.findConfirmedForDay(
                request.stationCode(), gasDate);
        if (readings.isEmpty()) {
            throw ApiException.badRequest("NO_CONFIRMED_READING",
                    "气量日 24:00（UTC）前没有已确认入库的读数，无法生成凭证");
        }

        BigDecimal totalVolume = readings.stream()
                .map(MeterReadingRow::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weightedHv = GasCalculation.weightedHeatingValue(readings);
        BigDecimal energy = GasCalculation.energy(totalVolume, weightedHv);
        String voucherNo = newVoucherNo("V", request.stationCode(), gasDate);

        try {
            // 数据库级占用，保证并发生成也只有一张
            voucherRepository.occupyStationDay(request.stationCode(), gasDate, voucherNo);
        } catch (DuplicateKeyException ex) {
            String raced = voucherRepository.findOccupiedVoucherNo(request.stationCode(), gasDate);
            throw ApiException.conflict("VOUCHER_ALREADY_EXISTS",
                    "同站同气量日已存在有效凭证: " + raced);
        }
        voucherRepository.insertOriginal(request.requestId(), voucherNo, request.stationCode(),
                gasDate, totalVolume, weightedHv, energy);

        // 表底时刻归属本日、但 24:00（UTC）后才入库的迟到读数：记差异记录，不改已生成凭证
        for (MeterReadingRow late : meterReadingRepository.findLateReadings(
                request.stationCode(), gasDate)) {
            voucherRepository.insertReadingDiff(voucherNo, late.id(), request.stationCode(),
                    gasDate, late.volume(), late.heatingValue());
        }

        return toVoucherResponse(voucherRepository.findByNo(voucherNo));
    }

    /**
     * 交接方确认：双方须在同一事务语义下先后确认，凭证方为“生效”；
     * 单方确认仅为 PARTIALLY_CONFIRMED，不生效。
     */
    @Transactional
    public VoucherResponse confirm(String voucherNo, ConfirmRequest request) {
        String fingerprint = canonical(TYPE_CONFIRM, request.requestId(), voucherNo, request.party());
        return withIdempotency(TYPE_CONFIRM, request.requestId(), fingerprint,
                VoucherResponse.class, () -> doConfirm(voucherNo, request));
    }

    private VoucherResponse doConfirm(String voucherNo, ConfirmRequest request) {
        VoucherRow voucher = requireVoucher(voucherNo);
        if (VoucherRow.TYPE_ADJUSTMENT.equals(voucher.voucherType())) {
            throw ApiException.conflict("ADJUSTMENT_NOT_CONFIRMABLE",
                    "调整凭证经双方签认提交即生效，无需再确认");
        }
        String status = voucher.status();
        if (VoucherRow.STATUS_SEALED.equals(status)) {
            throw ApiException.conflict("VOUCHER_SEALED", "凭证已封账，确认为终态后禁止操作");
        }
        if (VoucherRow.STATUS_DISPUTED.equals(status)) {
            throw ApiException.conflict("VOUCHER_DISPUTED", "凭证处于争议挂起状态，不能继续确认");
        }
        if (VoucherRow.STATUS_EFFECTIVE.equals(status)
                || voucherRepository.isConfirmedBy(voucherNo, request.party())) {
            // 重复确认（同方再次确认或凭证已生效）：返回当前状态，不产生新记录
            return toVoucherResponse(voucherRepository.findByNo(voucherNo));
        }
        if (qualityRepository.isConfirmPaused(voucherNo)) {
            // 气质差异未解决：尚未生效的凭证暂停双方确认，已有确认记录保留
            throw ApiException.conflict("CONFIRM_PAUSED",
                    "气质差异未解决，双方确认暂停；待双方签认差异后解除");
        }

        java.time.Instant now = clock.instant();
        voucherRepository.insertConfirmation(voucherNo, request.party(), request.requestId(), now);
        String otherParty = "A".equals(request.party()) ? "B" : "A";
        if (voucherRepository.isConfirmedBy(voucherNo, otherParty)) {
            // 双方确认齐备：同一事务内置为生效
            LocalDate effectiveDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
            voucherRepository.markEffective(voucherNo, effectiveDate, now);
        } else {
            // 单方确认不生效
            voucherRepository.updateStatus(voucherNo,
                    VoucherRow.STATUS_PARTIALLY_CONFIRMED, request.party());
        }
        return toVoucherResponse(voucherRepository.findByNo(voucherNo));
    }

    /**
     * 争议：凭证生效后进入 7 个自然日争议期（UTC 日界）；
     * 恰在第 7 日发起仍受理；逾期则封账为终态并拒绝争议。
     */
    @Transactional
    public VoucherResponse dispute(String voucherNo, DisputeRequest request) {
        String fingerprint = canonical(TYPE_DISPUTE, request.requestId(), voucherNo,
                request.party(), request.reason());
        return withIdempotency(TYPE_DISPUTE, request.requestId(), fingerprint,
                VoucherResponse.class, () -> doDispute(voucherNo, request));
    }

    private VoucherResponse doDispute(String voucherNo, DisputeRequest request) {
        VoucherRow voucher = requireVoucher(voucherNo);
        if (voucher.effectiveDate() == null) {
            throw ApiException.badRequest("VOUCHER_NOT_EFFECTIVE", "凭证尚未生效，不能发起争议");
        }
        if (VoucherRow.STATUS_SEALED.equals(voucher.status())) {
            throw ApiException.conflict("VOUCHER_SEALED", "凭证已封账，争议一律拒绝");
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate lastDisputeDay = voucher.effectiveDate().plusDays(DISPUTE_WINDOW_DAYS);
        if (today.isAfter(lastDisputeDay)) {
            // 逾期：在独立事务中封账为终态（不随本次拒绝回滚），再拒绝争议
            terminalSealer.sealIfOpenInNewTransaction(voucherNo);
            throw ApiException.conflict("DISPUTE_WINDOW_CLOSED",
                    "争议期已于 " + lastDisputeDay + "（UTC）结束，凭证封账");
        }

        if (VoucherRow.STATUS_DISPUTED.equals(voucher.status())) {
            // 争议已存在：返回挂起状态，唯一结果不重复落库
            return toVoucherResponse(voucherRepository.findByNo(voucherNo));
        }

        java.time.Instant now = clock.instant();
        int updated = voucherRepository.markDisputedIfEffective(voucherNo);
        if (updated == 0) {
            // 并发：可能已被另一争议抢先或已封账
            VoucherRow latest = voucherRepository.findByNo(voucherNo);
            if (VoucherRow.STATUS_SEALED.equals(latest.status())) {
                throw ApiException.conflict("VOUCHER_SEALED", "凭证已封账，争议一律拒绝");
            }
            if (voucherRepository.disputeExists(voucherNo)) {
                return toVoucherResponse(latest);
            }
            throw ApiException.conflict("DISPUTE_REJECTED", "争议未能受理，请刷新凭证状态");
        }
        voucherRepository.insertDispute(voucherNo, request.requestId(),
                request.party(), request.reason(), now);
        return toVoucherResponse(voucherRepository.findByNo(voucherNo));
    }

    /**
     * 封账为终态；重复封账幂等成功；争议期内拒绝封账；CAS 保证并发唯一结果。
     */
    @Transactional
    public VoucherResponse seal(String voucherNo, SealRequest request) {
        String fingerprint = canonical(TYPE_SEAL, request.requestId(), voucherNo);
        return withIdempotency(TYPE_SEAL, request.requestId(), fingerprint,
                VoucherResponse.class, () -> doSeal(voucherNo));
    }

    private VoucherResponse doSeal(String voucherNo) {
        VoucherRow voucher = requireVoucher(voucherNo);
        if (VoucherRow.STATUS_SEALED.equals(voucher.status())) {
            // 重复封账：幂等成功
            return toVoucherResponse(voucher);
        }
        if (voucher.effectiveDate() == null) {
            throw ApiException.badRequest("VOUCHER_NOT_EFFECTIVE", "凭证尚未生效，不能封账");
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate lastDisputeDay = voucher.effectiveDate().plusDays(DISPUTE_WINDOW_DAYS);
        if (!today.isAfter(lastDisputeDay)) {
            throw ApiException.conflict("SEAL_TOO_EARLY",
                    "争议期结束（" + lastDisputeDay + " UTC）前不能封账");
        }
        sealIfOpen(voucherNo);
        return toVoucherResponse(voucherRepository.findByNo(voucherNo));
    }

    /** 条件封账：仅 EFFECTIVE/DISPUTED 可置为 SEALED。 */
    private void sealIfOpen(String voucherNo) {
        int updated = voucherRepository.sealIfOpen(voucherNo, clock.instant());
        if (updated == 0) {
            VoucherRow latest = voucherRepository.findByNo(voucherNo);
            if (!VoucherRow.STATUS_SEALED.equals(latest.status())) {
                throw ApiException.conflict("SEAL_REJECTED", "凭证当前状态不能封账: " + latest.status());
            }
        }
    }

    /**
     * 封账后差错处理：双方签认的调整凭证引用原凭证号，提交即生效。
     */
    @Transactional
    public VoucherResponse adjust(String voucherNo, AdjustmentRequest request) {
        String fingerprint = canonical(TYPE_ADJUSTMENT, request.requestId(), voucherNo,
                request.volume().stripTrailingZeros().toPlainString(),
                request.heatingValue().stripTrailingZeros().toPlainString(),
                String.valueOf(request.signOffA()), String.valueOf(request.signOffB()));
        return withIdempotency(TYPE_ADJUSTMENT, request.requestId(), fingerprint,
                VoucherResponse.class, () -> doAdjust(voucherNo, request));
    }

    private VoucherResponse doAdjust(String voucherNo, AdjustmentRequest request) {
        VoucherRow original = requireVoucher(voucherNo);
        if (VoucherRow.TYPE_ADJUSTMENT.equals(original.voucherType())) {
            throw ApiException.badRequest("ADJUSTMENT_OF_ADJUSTMENT", "不能对调整凭证再开调整凭证");
        }
        if (!VoucherRow.STATUS_SEALED.equals(original.status())) {
            throw ApiException.conflict("ORIGINAL_NOT_SEALED",
                    "原始凭证须封账后，差错方可经双方签认的调整凭证处理");
        }
        if (!Boolean.TRUE.equals(request.signOffA()) || !Boolean.TRUE.equals(request.signOffB())) {
            throw ApiException.badRequest("SIGN_OFF_REQUIRED", "调整凭证须经交接双方签认");
        }
        if (voucherRepository.findAdjustmentOf(voucherNo) != null) {
            throw ApiException.conflict("ADJUSTMENT_EXISTS", "该原始凭证已存在调整凭证");
        }

        BigDecimal energy = GasCalculation.energy(request.volume(), request.heatingValue());
        String adjustmentNo = newVoucherNo("A", original.stationCode(), original.gasDate());
        try {
            voucherRepository.insertAdjustment(request.requestId(), adjustmentNo, voucherNo,
                    original.stationCode(), original.gasDate(), request.volume(),
                    request.heatingValue(), energy, clock.instant());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("ADJUSTMENT_EXISTS", "该原始凭证已存在调整凭证");
        }
        return toVoucherResponse(voucherRepository.findByNo(adjustmentNo));
    }

    /** 查询凭证。 */
    public VoucherResponse getVoucher(String voucherNo) {
        return toVoucherResponse(requireVoucher(voucherNo));
    }

    /**
     * 逐日复算偏差考核：实际能量取自最新有效凭证（调整凭证优先），
     * 偏差绝对值不超过日指定 3%（含）免考，超出部分×单价计违约金；结果稳定落库。
     */
    @Transactional
    public AssessmentResponse assessDay(String stationCode, LocalDate gasDate) {
        requireStation(stationCode);
        BigDecimal nominated = referenceDataRepository.findNomination(stationCode, gasDate);
        if (nominated == null) {
            throw ApiException.badRequest("NOMINATION_NOT_FOUND",
                    "缺少该气量日的日指定气量: " + gasDate);
        }
        BigDecimal unitPrice = referenceDataRepository.findPenaltyUnitPrice(stationCode);
        if (unitPrice == null) {
            throw ApiException.badRequest("CONTRACT_NOT_FOUND", "缺少管输合同约定单价");
        }

        VoucherRow original = voucherRepository.findOriginalByStationAndDate(stationCode, gasDate);
        if (original == null) {
            throw ApiException.badRequest("VOUCHER_NOT_FOUND", "该气量日尚无凭证，无法考核");
        }
        VoucherRow basis = voucherRepository.findAdjustmentOf(original.voucherNo());
        if (basis == null) {
            basis = original;
        }
        if (basis.effectiveDate() == null) {
            throw ApiException.conflict("VOUCHER_NOT_EFFECTIVE", "凭证尚未经双方确认生效，暂不考核");
        }

        BigDecimal deviation = GasCalculation.deviation(basis.energy(), nominated);
        BigDecimal deviationAbs = deviation.abs();
        BigDecimal tolerance = GasCalculation.toleranceEnergy(nominated);
        boolean exempt = deviationAbs.compareTo(tolerance) <= 0;
        BigDecimal penalty = GasCalculation.penalty(deviationAbs, tolerance, unitPrice);

        AssessmentRow row = new AssessmentRow(stationCode, gasDate, basis.voucherNo(),
                nominated.setScale(2), basis.energy(), deviation, deviationAbs,
                tolerance, exempt, penalty);
        assessmentRepository.upsert(row);
        return toAssessmentResponse(row);
    }

    /** 查询某站已复算落库的考核明细（按气量日升序，结果稳定）。 */
    public List<AssessmentResponse> listAssessments(String stationCode) {
        requireStation(stationCode);
        return assessmentRepository.findByStation(stationCode).stream()
                .map(CustodyService::toAssessmentResponse)
                .toList();
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void requireStation(String stationCode) {
        if (!referenceDataRepository.stationExists(stationCode)) {
            throw ApiException.notFound("STATION_NOT_FOUND", "交接站不存在: " + stationCode);
        }
    }

    private VoucherRow requireVoucher(String voucherNo) {
        VoucherRow voucher = voucherRepository.findByNo(voucherNo);
        if (voucher == null) {
            throw ApiException.notFound("VOUCHER_NOT_FOUND", "凭证不存在: " + voucherNo);
        }
        return voucher;
    }

    private String newVoucherNo(String prefix, String stationCode, LocalDate gasDate) {
        return prefix + "-" + stationCode + "-" + gasDate + "-"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0xFFFFFF));
    }

    /**
     * 幂等包装：同键同参返回首次响应快照；同键异参 409；并发同键落败者重查裁决。
     */
    private <T> T withIdempotency(String requestType, String requestId, String fingerprint,
                                  Class<T> responseType, java.util.function.Supplier<T> action) {
        String existingFingerprint = idempotencyRepository.findFingerprint(requestId);
        if (existingFingerprint != null) {
            if (!existingFingerprint.equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "相同 requestId 提交了不同的业务参数");
            }
            return readSnapshot(requestId, responseType);
        }

        T result = action.get();
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应快照序列化失败", ex);
        }
        try {
            idempotencyRepository.insert(requestId, requestType, fingerprint, snapshot);
        } catch (DuplicateKeyException ex) {
            // 并发同键：以先提交者的指纹/快照为准
            String racedFingerprint = idempotencyRepository.findFingerprint(requestId);
            if (racedFingerprint == null) {
                throw ApiException.conflict("IDEMPOTENCY_RACE", "幂等并发冲突，请稍后重试");
            }
            if (!racedFingerprint.equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "相同 requestId 提交了不同的业务参数");
            }
            return readSnapshot(requestId, responseType);
        }
        return result;
    }

    private <T> T readSnapshot(String requestId, Class<T> responseType) {
        String json = idempotencyRepository.findResponse(requestId);
        try {
            return objectMapper.readValue(json, responseType);
        } catch (Exception ex) {
            log.warn("幂等响应快照反序列化失败 requestId={}", requestId, ex);
            throw ApiException.badRequest("IDEMPOTENCY_SNAPSHOT_INVALID", "首次响应快照不可读");
        }
    }

    private static String canonical(Object... parts) {
        return String.join("|", java.util.Arrays.stream(parts).map(String::valueOf).toList());
    }

    static VoucherResponse toVoucherResponse(VoucherRow row) {
        return new VoucherResponse(row.voucherNo(), row.requestId(), row.voucherType(),
                row.originalVoucherNo(), row.stationCode(), row.gasDate(), row.volume(),
                row.weightedHeatingValue(), row.energy(), row.status(), row.firstConfirmParty(),
                row.effectiveDate(), row.effectiveAt(), row.sealedAt());
    }

    static AssessmentResponse toAssessmentResponse(AssessmentRow row) {
        return new AssessmentResponse(row.stationCode(), row.gasDate(), row.voucherNo(),
                row.nominatedEnergy(), row.actualEnergy(), row.deviation(), row.deviationAbs(),
                row.toleranceEnergy(), row.exempt(), row.penalty());
    }
}
