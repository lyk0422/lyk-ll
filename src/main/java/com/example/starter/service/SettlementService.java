package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ApiRequests.SettlementRequest;
import com.example.starter.api.dto.ApiRequests.SpeciesQuantity;
import com.example.starter.api.dto.ApiResponses.SettlementResponse;
import com.example.starter.domain.VoyageStatus;
import com.example.starter.repo.ArrivalDao;
import com.example.starter.repo.QuotaDao;
import com.example.starter.repo.QuotaDao.LedgerRow;
import com.example.starter.repo.ReviewDao;
import com.example.starter.repo.SettlementDao;
import com.example.starter.repo.SettlementDao.SettlementItemRow;
import com.example.starter.repo.VoyageDao;
import com.example.starter.repo.VoyageDao.VoyageRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配额核销：核销单与余额扣减在同一事务内完成；台账行锁保证并发核销不合计超配额。
 */
@Service
public class SettlementService {

    private final VoyageDao voyageDao;
    private final QuotaDao quotaDao;
    private final ArrivalDao arrivalDao;
    private final SettlementDao settlementDao;
    private final ReviewDao reviewDao;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public SettlementService(VoyageDao voyageDao, QuotaDao quotaDao, ArrivalDao arrivalDao,
                             SettlementDao settlementDao, ReviewDao reviewDao,
                             IdempotencyService idempotency, Clock businessClock) {
        this.voyageDao = voyageDao;
        this.quotaDao = quotaDao;
        this.arrivalDao = arrivalDao;
        this.settlementDao = settlementDao;
        this.reviewDao = reviewDao;
        this.idempotency = idempotency;
        this.clock = businessClock;
    }

    /** 配额核销：以进港卸渔量为口径扣减配额余额，航次流转为 SETTLED。 */
    @Transactional
    public SettlementResponse settle(long voyageId, SettlementRequest req) {
        return idempotency.execute(req.requestId(), "SETTLEMENT#" + voyageId, req, SettlementResponse.class,
                () -> {
                    VoyageRow voyage = voyageDao.findVoyage(voyageId)
                            .orElseThrow(() -> ApiException.notFound("航次不存在: " + voyageId));
                    if (voyage.status() == VoyageStatus.SETTLED || voyage.status() == VoyageStatus.CLOSED) {
                        throw ApiException.conflict("航次已完成配额核销，请勿重复核销");
                    }
                    if (voyage.status() != VoyageStatus.ARRIVED) {
                        throw ApiException.unprocessable("INVALID_STATE",
                                "航次状态 " + voyage.status() + " 不允许核销，须先提交进港报告");
                    }
                    if (reviewDao.hasOpenReview(voyageId)) {
                        throw ApiException.unprocessable("REVIEW_PENDING",
                                "航次存在待复核案件，复核办结前不得核销");
                    }
                    List<SpeciesQuantity> items = arrivalItemsOf(voyageId);
                    deductInSameTx(voyage, req.requestId(), items);
                    return new SettlementResponse(voyageId, VoyageStatus.SETTLED.name(), items);
                });
    }

    /** 航次进港报告的卸渔明细（核销口径）。 */
    List<SpeciesQuantity> arrivalItemsOf(long voyageId) {
        ArrivalDao.ArrivalRow arrival = arrivalDao.findByVoyage(voyageId)
                .orElseThrow(() -> ApiException.unprocessable("INVALID_STATE", "航次缺少进港报告"));
        return arrivalDao.itemsOf(arrival.id()).stream()
                .map(i -> new SpeciesQuantity(i.speciesCode(), i.unloadedKg()))
                .toList();
    }

    /**
     * 在当前事务内完成配额扣减、核销单落库与航次状态流转。
     * 供核销接口与复核办结（解除冻结与扣减同事务）复用；调用方须处于事务中。
     */
    void deductInSameTx(VoyageRow voyage, String requestId, List<SpeciesQuantity> items) {
        int quotaYear = voyage.departureTime().atZone(ZoneOffset.UTC).getYear();
        Map<String, LedgerRow> ledgers = quotaDao.lockLedgers(voyage.vesselId(), quotaYear)
                .stream().collect(Collectors.toMap(LedgerRow::speciesCode, Function.identity()));
        for (LedgerRow ledger : ledgers.values()) {
            if (ledger.frozen()) {
                throw ApiException.unprocessable("LEDGER_FROZEN",
                        "该船配额余额处于复核冻结中，复核办结前不得核销");
            }
        }
        for (SpeciesQuantity item : items) {
            LedgerRow ledger = ledgers.get(item.speciesCode());
            if (ledger == null) {
                throw ApiException.unprocessable("QUOTA_NOT_FOUND",
                        "鱼种 " + item.speciesCode() + " 在 " + quotaYear + " 年度无配额台账");
            }
            BigDecimal cumulative = ledger.usedQuota().add(item.quantityKg());
            if (cumulative.compareTo(ledger.totalQuota()) > 0) {
                throw ApiException.unprocessable("QUOTA_EXCEEDED",
                        "鱼种 " + item.speciesCode() + " 核销后累计 " + cumulative
                                + "kg 超出年度配额 " + ledger.totalQuota() + "kg");
            }
        }
        long settlementId = settlementDao.insertSettlement(requestId, voyage.id(), Instant.now(clock));
        settlementDao.insertItems(items.stream()
                .map(i -> new SettlementItemRow(settlementId, i.speciesCode(), i.quantityKg()))
                .toList());
        for (SpeciesQuantity item : items) {
            quotaDao.deduct(ledgers.get(item.speciesCode()).id(), item.quantityKg());
        }
        voyageDao.updateStatus(voyage.id(), VoyageStatus.SETTLED, null, null);
    }
}
