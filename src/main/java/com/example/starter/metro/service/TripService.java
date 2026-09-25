package com.example.starter.metro.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.metro.MetroException;
import com.example.starter.metro.domain.Trip;
import com.example.starter.metro.fare.FareService;
import com.example.starter.metro.fare.FareSplitter;
import com.example.starter.metro.fare.FareSplitter.Allocation;
import com.example.starter.metro.network.MileageRepository;
import com.example.starter.metro.network.MileageRepository.LineMileage;
import com.example.starter.metro.repo.ClearingRepository;
import com.example.starter.metro.repo.TransactionRepository;
import com.example.starter.metro.repo.TripRepository;
import com.example.starter.metro.web.dto.EntryRequest;
import com.example.starter.metro.web.dto.ExitRequest;
import com.example.starter.metro.web.dto.TripResponse;

/**
 * 进出闸交易服务：进闸建行、出闸计费清分。
 * 换乘链：同一票卡上次出闸至本次进闸 30 分钟内（含恰 30 分钟）连续换乘，只计一次起步价。
 * 超时乘车：进闸至出闸超过 4 小时（恰 4 小时不罚）按线网最高票价补收。
 * 出闸缺失：日结按最远站票价暂记；补登出闸在同一事务内冲销暂记并按实结算。
 */
@Service
public class TripService {

    /** 连续换乘窗口：上次出闸至本次进闸不超过 30 分钟（含恰 30 分钟）。 */
    static final Duration TRANSFER_WINDOW = Duration.ofMinutes(30);
    /** 超时乘车阈值：超过 4 小时补收，恰 4 小时不罚。 */
    static final Duration OVERTIME_THRESHOLD = Duration.ofHours(4);
    /** 暂记/冲销明细的占位线路编码。 */
    static final String PROVISIONAL_LINE = "PROV";

    private final TransactionRepository transactionRepository;
    private final TripRepository tripRepository;
    private final ClearingRepository clearingRepository;
    private final MileageRepository mileageRepository;
    private final FareService fareService;
    private final FareSplitter fareSplitter;
    private final Clock clock;

    public TripService(TransactionRepository transactionRepository, TripRepository tripRepository,
            ClearingRepository clearingRepository, MileageRepository mileageRepository,
            FareService fareService, FareSplitter fareSplitter, Clock clock) {
        this.transactionRepository = transactionRepository;
        this.tripRepository = tripRepository;
        this.clearingRepository = clearingRepository;
        this.mileageRepository = mileageRepository;
        this.fareService = fareService;
        this.fareSplitter = fareSplitter;
        this.clock = clock;
    }

    /**
     * 进闸：登记流水并新建 OPEN 行程。
     */
    @Transactional
    public TripResponse entry(EntryRequest request) {
        requireStation(request.station());
        insertTransaction(request.txNo(), request.requestId(), request.ticketId(), "ENTRY",
                request.station(), request.occurredAt());
        tripRepository.findOpenOrProvisionalForUpdate(request.ticketId()).ifPresent(trip -> {
            throw new MetroException.InvalidRequest(
                    "票卡存在未出闸行程，禁止重复进闸: " + request.ticketId());
        });
        LocalDateTime now = LocalDateTime.now(clock);
        long tripId = tripRepository.insertOpen(request.ticketId(), request.txNo(),
                request.station(), request.occurredAt(), now);
        return new TripResponse(tripId, request.ticketId(), Trip.STATUS_OPEN, request.station(),
                null, null, null, null, null, null, false, false);
    }

    /**
     * 出闸（含补登）：计费、跨线分摊、批次归属；暂记行程在同一事务内冲销并按实结算。
     */
    @Transactional
    public TripResponse exit(ExitRequest request) {
        requireStation(request.station());
        insertTransaction(request.txNo(), request.requestId(), request.ticketId(), "EXIT",
                request.station(), request.occurredAt());
        Trip trip = tripRepository.findOpenOrProvisionalForUpdate(request.ticketId())
                .orElseThrow(() -> new MetroException.TripNotFound(request.ticketId()));
        if (request.occurredAt().isBefore(trip.entryTime())) {
            throw new MetroException.InvalidRequest("出闸时间早于进闸时间");
        }
        Integer distanceKm = mileageRepository.distanceKm(trip.entryStation(), request.station());
        if (distanceKm == null) {
            throw new MetroException.InvalidRequest(
                    "站点间无里程数据: " + trip.entryStation() + "->" + request.station());
        }

        // 换乘链：上次出闸至本次进闸 30 分钟内（含恰 30 分钟）续链，只计一次起步价
        long chainCumBeforeKm = 0L;
        boolean transferChained = false;
        var previous = tripRepository.findLatestSettledBefore(request.ticketId(), trip.entryTime());
        if (previous.isPresent()
                && !previous.get().exitTime().plus(TRANSFER_WINDOW).isBefore(trip.entryTime())) {
            chainCumBeforeKm = previous.get().chainCumKm();
            transferChained = true;
        }
        long chainCumKm = chainCumBeforeKm + distanceKm;
        long fareFen = fareService.chainedFareFen(chainCumBeforeKm, distanceKm);

        // 超时乘车：进闸至出闸超过 4 小时（恰 4 小时不罚）按线网最高票价补收
        boolean overtime = Duration.between(trip.entryTime(), request.occurredAt())
                .compareTo(OVERTIME_THRESHOLD) > 0;
        if (overtime) {
            fareFen += fareService.fareFenForKm(mileageRepository.maxNetworkDistanceKm());
        }

        // 批次归属：封账批次不再入账，迟到交易顺延至未封账批次并登记差异
        LocalDate naturalDate = request.occurredAt().toLocalDate();
        LocalDate batchDate = firstUnsealedDate(naturalDate);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!batchDate.equals(naturalDate)) {
            clearingRepository.insertDiff(naturalDate, batchDate, request.txNo(), trip.id(),
                    ClearingRepository.REASON_LATE_AFTER_SEAL, now);
        }

        // 暂记冲销：同一事务内先冲销暂记，再按实结算，不双边计额
        if (Trip.STATUS_PROVISIONAL.equals(trip.status())) {
            clearingRepository.insertDetail(batchDate, trip.id(), request.txNo(),
                    PROVISIONAL_LINE, 0, new BigDecimal("1.0000"), -trip.provisionalFareFen(),
                    ClearingRepository.DETAIL_REVERSAL, now);
        }

        // 跨线分摊：按承运里程比例，差额归入里程最长线路
        List<LineMileage> lines = mileageRepository.lineMileages(trip.entryStation(),
                request.station());
        for (Allocation allocation : fareSplitter.split(fareFen, lines)) {
            clearingRepository.insertDetail(batchDate, trip.id(), request.txNo(),
                    allocation.lineCode(), allocation.distanceKm(), allocation.ratio(),
                    allocation.amountFen(), ClearingRepository.DETAIL_SETTLE, now);
        }

        tripRepository.markSettled(trip.id(), request.txNo(), request.station(),
                request.occurredAt(), distanceKm, (int) chainCumKm, fareFen, batchDate, now);
        return new TripResponse(trip.id(), trip.ticketId(), Trip.STATUS_SETTLED,
                trip.entryStation(), request.station(), distanceKm, (int) chainCumKm, fareFen,
                trip.provisionalFareFen(), batchDate, overtime, transferChained);
    }

    /**
     * 查找首个未封账的批次日期（从自然归属日起顺延）。
     */
    private LocalDate firstUnsealedDate(LocalDate date) {
        LocalDate candidate = date;
        while (clearingRepository.findBatch(candidate).isPresent()) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private void requireStation(String station) {
        if (!mileageRepository.stationExists(station)) {
            throw new MetroException.InvalidRequest("站点不存在: " + station);
        }
    }

    private void insertTransaction(String txNo, String requestId, String ticketId, String txType,
            String station, LocalDateTime occurredAt) {
        try {
            transactionRepository.insert(txNo, requestId, ticketId, txType, station, occurredAt,
                    LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            throw new MetroException.DuplicateTransaction(txNo);
        }
    }
}
