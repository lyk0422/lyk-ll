package com.example.starter.metro.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.example.starter.metro.MetroException;
import com.example.starter.metro.domain.Trip;
import com.example.starter.metro.fare.FareService;
import com.example.starter.metro.network.MileageRepository;
import com.example.starter.metro.repo.ClearingRepository;
import com.example.starter.metro.repo.ClearingRepository.Batch;
import com.example.starter.metro.repo.TripRepository;
import com.example.starter.metro.web.dto.SealRequest;
import com.example.starter.metro.web.dto.SealResponse;

/**
 * 日结服务：封账为终态。
 * 封账前对出闸缺失的 OPEN 行程按最远站票价暂记；
 * 重复封账幂等成功；封账后合计不再变化，迟到交易顺延入账并登记差异。
 */
@Service
public class SettlementService {

    private final ClearingRepository clearingRepository;
    private final TripRepository tripRepository;
    private final MileageRepository mileageRepository;
    private final FareService fareService;
    private final Clock clock;

    public SettlementService(ClearingRepository clearingRepository, TripRepository tripRepository,
            MileageRepository mileageRepository, FareService fareService, Clock clock) {
        this.clearingRepository = clearingRepository;
        this.tripRepository = tripRepository;
        this.mileageRepository = mileageRepository;
        this.fareService = fareService;
        this.clock = clock;
    }

    /**
     * 日结封账：暂记 OPEN 行程 -> 核定合计 -> 写入终态批次。
     * 已封账时直接返回既有合计（幂等成功），不修改任何数据。
     */
    @Transactional
    public SealResponse seal(SealRequest request) {
        LocalDate batchDate = request.batchDate();
        Optional<Batch> existing = clearingRepository.findBatch(batchDate);
        if (existing.isPresent()) {
            Batch batch = existing.get();
            return new SealResponse(batch.batchDate(), batch.status(), batch.totalFen(), true);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Trip> openTrips = tripRepository.findOpenTripsEnteredOnOrBefore(batchDate);
        for (Trip trip : openTrips) {
            long provisionalFare = fareService.fareFenForKm(
                    mileageRepository.maxDistanceKmFrom(trip.entryStation()));
            clearingRepository.insertDetail(batchDate, trip.id(), trip.entryTxNo(),
                    TripService.PROVISIONAL_LINE, 0, new BigDecimal("1.0000"), provisionalFare,
                    ClearingRepository.DETAIL_PROVISIONAL, now);
            tripRepository.markProvisional(trip.id(), provisionalFare, batchDate, now);
        }
        long totalFen = clearingRepository.sumForBatch(batchDate);
        boolean inserted = clearingRepository.insertSealedBatch(batchDate, request.requestId(),
                totalFen, now);
        if (!inserted) {
            // 并发封账竞态失败：回滚本方暂记明细，返回胜方合计（幂等成功）
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            Batch winner = clearingRepository.findBatch(batchDate)
                    .orElseThrow(() -> new MetroException(409, "封账并发冲突: " + batchDate));
            return new SealResponse(winner.batchDate(), winner.status(), winner.totalFen(), true);
        }
        return new SealResponse(batchDate, ClearingRepository.BATCH_SEALED, totalFen, false);
    }
}
