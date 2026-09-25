package com.example.starter.dye.service;

import com.example.starter.dye.domain.ColorAssessmentPO;
import com.example.starter.dye.domain.OperationType;
import com.example.starter.dye.repo.ColorAssessmentRepository;
import com.example.starter.dye.web.dto.ColorMatchRequest;
import com.example.starter.dye.web.dto.ColorMatchResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 打小样对色：CIE76 ΔE = sqrt(ΔL² + Δa² + Δb²)，保留 4 位小数（HALF_UP）。
 * 放行门限 ΔE ≤ 0.8，恰 0.8 合格。requestId 幂等，同键异参 409。
 */
@Service
public class ColorMatchService {

    /** 对色放行门限（含边界） */
    public static final BigDecimal PASS_THRESHOLD = new BigDecimal("0.8");

    private static final int DELTA_SCALE = 4;

    private final IdempotencyService idempotency;
    private final ColorAssessmentRepository repository;
    private final Clock clock;

    public ColorMatchService(IdempotencyService idempotency, ColorAssessmentRepository repository,
                             Clock clock) {
        this.idempotency = idempotency;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Outcome<ColorMatchResponse> match(ColorMatchRequest request) {
        return idempotency.execute(OperationType.COLOR_MATCH, request.requestId(), request, () -> {
            BigDecimal deltaE = cie76(request);
            boolean passed = deltaE.compareTo(PASS_THRESHOLD) <= 0;
            ColorAssessmentPO po = new ColorAssessmentPO();
            po.setRequestId(request.requestId());
            po.setSampleCode(request.sampleCode());
            po.setStdL(request.stdL());
            po.setStdA(request.stdA());
            po.setStdB(request.stdB());
            po.setSampleL(request.sampleL());
            po.setSampleA(request.sampleA());
            po.setSampleB(request.sampleB());
            po.setDeltaE(deltaE);
            po.setPassed(passed);
            repository.insert(po, LocalDateTime.now(clock));
            return IdempotencyService.ServiceResult.ok(
                    new ColorMatchResponse(request.requestId(), request.sampleCode(), deltaE, passed));
        });
    }

    /**
     * CIE76 色差；服务端复算，不信任客户端任何 ΔE 值。
     */
    public static BigDecimal cie76(ColorMatchRequest r) {
        double dL = r.sampleL().doubleValue() - r.stdL().doubleValue();
        double da = r.sampleA().doubleValue() - r.stdA().doubleValue();
        double db = r.sampleB().doubleValue() - r.stdB().doubleValue();
        double delta = Math.sqrt(dL * dL + da * da + db * db);
        return BigDecimal.valueOf(delta).setScale(DELTA_SCALE, RoundingMode.HALF_UP);
    }
}
