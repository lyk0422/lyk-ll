package com.example.starter.feed.service;

import com.example.starter.feed.repo.BatchRepository;
import com.example.starter.feed.repo.ProductionRepository;
import com.example.starter.feed.repo.TreatmentReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 批次合并口径休药期重算：统一合并含药饲喂事件与治疗性用药直报，
 * 取各来源最大休药天数与最晚给药/饲喂时刻，重算后写回批次行。
 *
 * <p>调用方须已持有批次行锁（SELECT ... FOR UPDATE），本方法加入调用方事务，
 * 与触发方（投产/饲喂/治疗直报）的其它写入同事务提交或回滚。</p>
 */
@Service
public class WithdrawalRecalcService {

    private final BatchRepository batchRepository;
    private final ProductionRepository productionRepository;
    private final TreatmentReportRepository treatmentReportRepository;

    public WithdrawalRecalcService(BatchRepository batchRepository,
                                   ProductionRepository productionRepository,
                                   TreatmentReportRepository treatmentReportRepository) {
        this.batchRepository = batchRepository;
        this.productionRepository = productionRepository;
        this.treatmentReportRepository = treatmentReportRepository;
    }

    /**
     * 按批次全部非迟到的含药饲喂事件与治疗直报逐支复算合并休药期并写回批次行。
     * 迟到事件/迟到直报不影响已冻结义务，不参与合并。
     *
     * @return 合并结果；批次无任何休药义务时返回 null（批次休药字段同时清空）
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public WithdrawalCalculator.MergedWithdrawal recomputeAndApply(long batchId) {
        Instant latest = null;
        int maxDays = 0;

        List<ProductionRepository.FeedingRow> feedings =
                productionRepository.findFeedingsByBatch(batchId);
        for (ProductionRepository.FeedingRow feeding : feedings) {
            if (feeding.late()) {
                continue;
            }
            List<ProductionRepository.DrugSnapshotRow> snapshots =
                    productionRepository.findSnapshots(feeding.productionId());
            int feedingMaxDays = snapshots.stream()
                    .mapToInt(ProductionRepository.DrugSnapshotRow::withdrawalDays)
                    .max().orElse(0);
            maxDays = Math.max(maxDays, feedingMaxDays);
            if (latest == null || feeding.feedAtUtc().isAfter(latest)) {
                latest = feeding.feedAtUtc();
            }
        }

        List<TreatmentReportRepository.TreatmentReportRow> reports =
                treatmentReportRepository.findByBatch(batchId);
        for (TreatmentReportRepository.TreatmentReportRow report : reports) {
            if (report.late()) {
                continue;
            }
            // 合并口径：直报与饲喂统一取最大休药天数与最晚给药/饲喂时刻。
            maxDays = Math.max(maxDays, report.maxWithdrawalDays());
            if (latest == null || report.administeredAtUtc().isAfter(latest)) {
                latest = report.administeredAtUtc();
            }
        }

        if (latest == null) {
            batchRepository.updateWithdrawal(batchId, null, null, null);
            return null;
        }
        LocalDate untilDate = WithdrawalCalculator.withdrawalUntilDate(latest, maxDays);
        batchRepository.updateWithdrawal(batchId, latest, untilDate, maxDays);
        return new WithdrawalCalculator.MergedWithdrawal(
                latest, maxDays, untilDate, WithdrawalCalculator.earliestSlaughter(untilDate));
    }
}
