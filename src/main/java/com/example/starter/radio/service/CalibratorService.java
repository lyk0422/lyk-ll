package com.example.starter.radio.service;

import java.time.Clock;
import java.time.LocalDate;

import com.example.starter.radio.model.DailyCheck;
import com.example.starter.radio.repository.CalibratorRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活度计日检：以参考源校验，相对偏差绝对值恰 5% 判定通过，超过 5% 当日禁用。
 * 幂等：requestId 唯一，指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，同键异参 409。
 * 并发：同一活度计同一日仅允许一个校准会话，由 (calibrator_id, check_date) 唯一约束保证唯一受理。
 */
@Service
public class CalibratorService {

    /** 日检合格阈值：相对偏差绝对值不超过 5%。 */
    public static final double QC_TOLERANCE = 0.05;

    private final CalibratorRepository calibrators;
    private final Clock clock;

    public CalibratorService(CalibratorRepository calibrators, Clock clock) {
        this.calibrators = calibrators;
        this.clock = clock;
    }

    @Transactional
    public DailyCheck dailyCheck(String requestId, long calibratorId, LocalDate checkDate,
                                 double referenceActivityBq, double measuredActivityBq) {
        calibrators.findCalibratorById(calibratorId)
                .orElseThrow(() -> new NotFoundException("活度计不存在: " + calibratorId));
        String fingerprint = Fingerprints.of(requestId, calibratorId, checkDate,
                referenceActivityBq, measuredActivityBq);

        var existing = calibrators.findDailyCheckByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        calibrators.findDailyCheck(calibratorId, checkDate).ifPresent(other -> {
            throw new ConflictException("同一活度计当日已存在校准会话: calibratorId=" + calibratorId
                    + ", checkDate=" + checkDate);
        });

        double deviation = (measuredActivityBq - referenceActivityBq) / referenceActivityBq;
        boolean passed = Math.abs(deviation) <= QC_TOLERANCE;
        DailyCheck check = new DailyCheck(0L, requestId, fingerprint, calibratorId, checkDate,
                referenceActivityBq, measuredActivityBq, deviation, passed);
        try {
            long id = calibrators.insertDailyCheck(check, clock.instant().getEpochSecond());
            return calibrators.findDailyCheckByRequestId(requestId)
                    .filter(c -> c.id() == id)
                    .orElseThrow(() -> new IllegalStateException("日检记录写入后读取失败"));
        } catch (DuplicateKeyException e) {
            // 并发受理：唯一约束保证只有一条落库，其余按幂等/冲突处理
            return calibrators.findDailyCheckByRequestId(requestId)
                    .map(c -> replayOrConflict(c, fingerprint))
                    .orElseThrow(() -> new ConflictException(
                            "同一活度计当日校准会话并发冲突: calibratorId=" + calibratorId));
        }
    }

    private DailyCheck replayOrConflict(DailyCheck existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("相同 requestId 但业务字段不一致，拒绝重复受理: " + existing.requestId());
        }
        return existing;
    }
}
