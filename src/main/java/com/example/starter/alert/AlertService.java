package com.example.starter.alert;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.batch.Batch;
import com.example.starter.inspection.RapidTestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 产地预警服务：同一产地同品种当日（UTC 日界）累计 3 批疑似即触发预警（恰 3 批触发）；
 * 预警期间该产地同品种后续批次逐批加严抽检。台账可由快检记录复算。
 */
@Service
public class AlertService {

    /**
     * 预警阈值：当日累计疑似批次数达到该值即触发。
     */
    public static final int THRESHOLD = 3;

    private final OriginAlertRepository alertRepository;
    private final RapidTestRepository rapidTestRepository;

    public AlertService(OriginAlertRepository alertRepository, RapidTestRepository rapidTestRepository) {
        this.alertRepository = alertRepository;
        this.rapidTestRepository = rapidTestRepository;
    }

    /**
     * 批次判定疑似后调用：按 UTC 日界累计疑似批次数，达到阈值且当日未预警则登记预警。
     * 须在快检判定事务内调用，与判定同事务提交。
     */
    public void onSuspected(Batch batch, LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        long count = rapidTestRepository.countSuspected(batch.origin(), batch.variety(), date);
        if (count >= THRESHOLD && alertRepository.find(batch.origin(), batch.variety(), date).isEmpty()) {
            try {
                alertRepository.insert(batch.origin(), batch.variety(), date, (int) count, now);
            } catch (DuplicateKeyException ignored) {
                // 并发下同日同产地同品种只登记一次预警
            }
        }
    }

    /**
     * 判断指定 UTC 日期该产地同品种是否处于预警期（决定后续批次是否加严抽检）。
     */
    public boolean isAlertActive(String origin, String variety, LocalDate date) {
        return alertRepository.find(origin, variety, date).isPresent();
    }

    /**
     * 预警台账列表。
     */
    public List<OriginAlert> ledger() {
        return alertRepository.findAll();
    }

    /**
     * 复算：从快检记录重算当日疑似批次数，与台账比对，验证预警口径。
     */
    public AlertRecomputeResponse recompute(String origin, String variety, LocalDate date) {
        long count = rapidTestRepository.countSuspected(origin, variety, date);
        boolean alertExists = alertRepository.find(origin, variety, date).isPresent();
        return new AlertRecomputeResponse(origin, variety, date, count, THRESHOLD,
                count >= THRESHOLD, alertExists);
    }
}
