package com.example.starter.flood.service;

import com.example.starter.flood.domain.ChainStatus;
import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.IdempotentRepository;
import com.example.starter.flood.repo.IdempotentRepository.IdempotencyOutcome;
import com.example.starter.flood.repo.PondPO;
import com.example.starter.flood.repo.PondRepository;
import com.example.starter.flood.repo.WarningPO;
import com.example.starter.flood.repo.WarningRepository;
import com.example.starter.flood.support.BusinessNoGenerator;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.WarningRequest;
import com.example.starter.flood.web.dto.WarningView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 降雨预警/险情处置链服务；以 requestId 幂等，同键同参返回首次结果，同键异参 409。
 */
@Service
public class WarningService {

    private final WarningRepository warningRepository;
    private final PondRepository pondRepository;
    private final IdempotentRepository idempotentRepository;
    private final BusinessNoGenerator noGenerator;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public WarningService(WarningRepository warningRepository, PondRepository pondRepository,
                          IdempotentRepository idempotentRepository,
                          BusinessNoGenerator noGenerator, JsonSupport jsonSupport,
                          Clock floodClock) {
        this.warningRepository = warningRepository;
        this.pondRepository = pondRepository;
        this.idempotentRepository = idempotentRepository;
        this.noGenerator = noGenerator;
        this.jsonSupport = jsonSupport;
        this.clock = floodClock;
    }

    /**
     * 创建处置链（降雨预警/险情报送共用同一处置链入口）。
     */
    @Transactional
    public WarningView open(WarningRequest request) {
        PondPO pond = pondRepository.requireByCode(request.pondCode());
        String fingerprint = jsonSupport.fingerprint(fingerprintFields(request));
        LocalDateTime now = LocalDateTime.now(clock);

        IdempotencyOutcome outcome = idempotentRepository.register(
                request.requestId(), "WARNING", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的预警，请更换 requestId");
            }
            WarningPO existing = warningRepository.findByRequestId(request.requestId());
            return toView(existing, true);
        }

        WarningPO po = new WarningPO();
        po.warningNo = noGenerator.next("YW");
        po.pondId = pond.id;
        po.title = request.title();
        po.rainSummary = request.rainSummary();
        po.status = ChainStatus.ACTIVE.name();
        po.requestId = request.requestId();
        po.openedAt = now;
        warningRepository.insert(po);
        WarningPO saved = warningRepository.findByRequestId(request.requestId());
        idempotentRepository.bindRef(request.requestId(), saved.id);
        return toView(saved, false);
    }

    /**
     * 按编号查询；不存在抛 404。
     */
    public WarningPO requireByNo(String warningNo) {
        WarningPO warning = warningRepository.findByNo(warningNo);
        if (warning == null) {
            throw FloodControlException.notFound("处置链不存在: " + warningNo);
        }
        return warning;
    }

    /**
     * 校验处置链处于生效中。
     */
    public void requireActive(WarningPO warning) {
        if (ChainStatus.ARCHIVED.name().equals(warning.status)) {
            throw FloodControlException.business("CHAIN_ARCHIVED",
                    "处置链 " + warning.warningNo + " 已归档（终态），不能继续业务操作");
        }
    }

    /**
     * 按主键查询处置链（供其他服务拼装视图）。
     */
    public WarningPO findWarning(Long id) {
        return warningRepository.findById(id);
    }

    private Map<String, Object> fingerprintFields(WarningRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "WARNING");
        fields.put("pondCode", request.pondCode());
        fields.put("title", request.title());
        fields.put("rainSummary", request.rainSummary());
        return fields;
    }

    private WarningView toView(WarningPO po, boolean replay) {
        PondPO pond = pondRepository.findById(po.pondId);
        return new WarningView(po.warningNo, pond == null ? null : pond.pondCode, po.title,
                po.rainSummary, po.status, po.requestId, po.openedAt, po.archivedAt);
    }
}
