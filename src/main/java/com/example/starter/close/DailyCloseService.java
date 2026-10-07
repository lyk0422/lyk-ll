package com.example.starter.close;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.reading.OdorantReadingRepository;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.tank.Tank;
import com.example.starter.tank.TankLedgerRepository;
import com.example.starter.tank.TankRepository;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.DailyCloseResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 日结：加臭剂消耗与累计流量勾稽，恰 3% 合格封存，超差转人工。
 *
 * <ul>
 *   <li>理论消耗 = 当日有效检测累计流量 × 目标浓度（20mg/m³）；</li>
 *   <li>偏差 = |实际上报 − 理论| / 理论 × 100%；偏差 ≤ 3.00%（闭区间，恰 3% 合格）
 *       且无 MANUAL 未处理时封存为终态 SEALED；超过 3% 转人工 MANUAL；</li>
 *   <li>已封存（终态）：重复封存同参返回幂等成功，不重新计算、不改数据；
 *       同键异参 409（由幂等层保证）且不改阀门状态；</li>
 *   <li>MANUAL 状态下再次日结（不同 requestId、新数据）允许重新勾稽，达标后封存。</li>
 *   <li>展示：响应附带当日调拨转入/转出 kg（由台账分录实时复算），
 *       仅展示不改写 daily_close 封存行，旧日结快照保持不变。</li>
 * </ul>
 */
@Service
public class DailyCloseService {

    private static final BigDecimal ALLOWED_DEVIATION_PCT = new BigDecimal("3.0000");
    private static final int DEVIATION_SCALE = 4;

    private final StationRepository stationRepository;
    private final OdorantReadingRepository readingRepository;
    private final DailyCloseRepository dailyCloseRepository;
    private final TankRepository tankRepository;
    private final TankLedgerRepository tankLedgerRepository;
    private final IdempotencyService idempotencyService;

    public DailyCloseService(StationRepository stationRepository,
                             OdorantReadingRepository readingRepository,
                             DailyCloseRepository dailyCloseRepository,
                             TankRepository tankRepository,
                             TankLedgerRepository tankLedgerRepository,
                             IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.readingRepository = readingRepository;
        this.dailyCloseRepository = dailyCloseRepository;
        this.tankRepository = tankRepository;
        this.tankLedgerRepository = tankLedgerRepository;
        this.idempotencyService = idempotencyService;
    }

    public DailyCloseResponse close(DailyCloseRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<DailyCloseResponse> retry = idempotencyService.run(
                request.requestId(), station.id(), "DAILY_CLOSE", request, 200,
                () -> doClose(station, request), DailyCloseResponse.class);
        return retry.result();
    }

    private DailyCloseResponse toResponse(Station station, DailyCloseRow row, boolean replay) {
        BigDecimal[] transfers = transferSums(station.id(), row.bizDate());
        return new DailyCloseResponse(row.bizDate(), row.status(), row.totalFlowM3(),
                row.expectedOdorantMg(), row.reportedOdorantMg(), row.deviationPct(),
                "SEALED".equals(row.status()), row.sealedAt(), replay,
                transfers[0], transfers[1]);
    }

    /**
     * 当日调拨转入/转出 kg：由台账 TRANSFER_IN/TRANSFER_OUT 分录实时复算，
     * 仅用于展示，不改写封存行；门站未绑定储罐时为 0。
     */
    private BigDecimal[] transferSums(Long stationId, LocalDate bizDate) {
        Tank tank = tankRepository.findByStationId(stationId).orElse(null);
        if (tank == null) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal in = tankLedgerRepository.sumBalanceDeltaByTypeAndBizAt(tank.id(),
                "TRANSFER_IN", bizDate.atStartOfDay(), bizDate.plusDays(1).atStartOfDay());
        BigDecimal out = tankLedgerRepository.sumBalanceDeltaByTypeAndBizAt(tank.id(),
                "TRANSFER_OUT", bizDate.atStartOfDay(), bizDate.plusDays(1).atStartOfDay());
        return new BigDecimal[]{in, out.negate()};
    }

    private DailyCloseResponse doClose(Station station, DailyCloseRequest request) {
        LocalDate bizDate = request.bizDate();

        DailyCloseRow existing = dailyCloseRepository.findForUpdate(station.id(), bizDate).orElse(null);
        if (existing != null && "SEALED".equals(existing.status())) {
            // 封存为终态：重复封存返回首次封存结果（新请求同参的重放由幂等层标记 replay）
            return toResponse(station, existing, false);
        }

        BigDecimal totalFlow = readingRepository.sumFlowByDate(station.id(), bizDate);
        BigDecimal expected = totalFlow.multiply(station.targetConcentrationMgM3())
                .setScale(3, RoundingMode.HALF_UP);
        BigDecimal reported = request.reportedOdorantMg().setScale(3, RoundingMode.HALF_UP);
        BigDecimal deviation;
        if (expected.compareTo(BigDecimal.ZERO) == 0) {
            // 当日无检测流量：理论消耗为 0，仅当实际上报也为 0 时偏差 0%
            deviation = reported.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO.setScale(DEVIATION_SCALE, RoundingMode.HALF_UP)
                    : new BigDecimal("100.0000");
        } else {
            deviation = reported.subtract(expected).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(expected, DEVIATION_SCALE, RoundingMode.HALF_UP);
        }

        boolean sealed = deviation.compareTo(ALLOWED_DEVIATION_PCT) <= 0;
        String status = sealed ? "SEALED" : "MANUAL";

        if (existing == null) {
            try {
                dailyCloseRepository.insert(station.id(), bizDate, status, totalFlow, expected,
                        reported, deviation, sealed ? request.requestId() : null, sealed);
            } catch (org.springframework.dao.DuplicateKeyException concurrent) {
                // 不同 requestId 并发日结同一日：唯一约束裁决，回查首个落库结果
                DailyCloseRow winner = dailyCloseRepository.findForUpdate(station.id(), bizDate)
                        .orElseThrow();
                return toResponse(station, winner, false);
            }
        } else {
            // MANUAL → 达标封存 / 仍超差保持转人工
            dailyCloseRepository.update(station.id(), bizDate, status, reported, deviation,
                    sealed ? request.requestId() : null, sealed);
        }

        DailyCloseRow saved = dailyCloseRepository.findForUpdate(station.id(), bizDate).orElseThrow();
        return toResponse(station, saved, false);
    }
}
