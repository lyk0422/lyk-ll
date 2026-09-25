package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.AlertResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产地预警服务：同产地同品种当日（UTC 日界）累计 3 批疑似触发，恰 3 批触发；台账可复算。
 */
@Service
public class AlertService {

    /** 触发预警的疑似批次数阈值（恰达到即触发） */
    public static final int ALERT_THRESHOLD = 3;

    private final TestDao testDao;
    private final LedgerDao ledgerDao;
    private final Clock clock;

    public AlertService(TestDao testDao, LedgerDao ledgerDao, Clock clock) {
        this.testDao = testDao;
        this.ledgerDao = ledgerDao;
        this.clock = clock;
    }

    /**
     * 疑似快检产生后尝试触发产地预警（须在快检事务内调用，保证统计与登记原子）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void maybeTrigger(String origin, String variety, LocalDateTime testTimeUtc) {
        LocalDate alertDate = testTimeUtc.toLocalDate();
        if (ledgerDao.alertExists(origin, variety, alertDate)) {
            return;
        }
        long suspectBatches = countSuspectBatches(origin, variety, alertDate);
        if (suspectBatches >= ALERT_THRESHOLD) {
            try {
                ledgerDao.insertAlert(origin, variety, alertDate, (int) suspectBatches, nowUtc());
            } catch (DuplicateKeyException e) {
                // 并发触发：唯一约束保证台账仅一条
            }
        }
    }

    /**
     * 查询预警台账。
     */
    @Transactional(readOnly = true)
    public List<AlertResponse> list() {
        return ledgerDao.findAllAlerts().stream().map(this::toResponse).toList();
    }

    /**
     * 按快检记录复算预警台账：对每个产地+品种+UTC 日期统计疑似批次数，达到阈值且缺失则补登。
     */
    @Transactional
    public List<AlertResponse> recompute() {
        Map<String, Set<Long>> grouped = new HashMap<>();
        Map<String, LocalDate> dates = new HashMap<>();
        for (TestDao.SuspectRow row : testDao.findAllSuspects()) {
            LocalDate date = row.createdAt().toLocalDate();
            String key = row.origin() + '|' + row.variety() + '|' + date;
            grouped.computeIfAbsent(key, k -> new HashSet<>()).add(row.batchId());
            dates.put(key, date);
        }
        grouped.forEach((key, batchIds) -> {
            if (batchIds.size() >= ALERT_THRESHOLD) {
                String[] parts = key.split("\\|");
                LocalDate date = dates.get(key);
                if (!ledgerDao.alertExists(parts[0], parts[1], date)) {
                    try {
                        ledgerDao.insertAlert(parts[0], parts[1], date, batchIds.size(), nowUtc());
                    } catch (DuplicateKeyException e) {
                        // 并发复算：唯一约束兜底
                    }
                }
            }
        });
        return ledgerDao.findAllAlerts().stream().map(this::toResponse).toList();
    }

    private long countSuspectBatches(String origin, String variety, LocalDate date) {
        return testDao.findSuspects(origin, variety).stream()
                .filter(row -> row.createdAt().toLocalDate().equals(date))
                .map(TestDao.SuspectRow::batchId)
                .distinct()
                .count();
    }

    private AlertResponse toResponse(LedgerDao.AlertRow row) {
        return new AlertResponse(row.id(), row.origin(), row.variety(), row.alertDate(),
                row.triggerCount(), row.status(), row.createdAt().toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
