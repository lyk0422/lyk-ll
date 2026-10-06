package com.example.starter.pest;

import static com.example.starter.common.IdempotencyService.fingerprint;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotencyService;
import com.example.starter.pest.api.Requests.OutboundRequest;
import com.example.starter.pest.api.Results.OutboundResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出库登记：处置期间仅禁止该库区出库，其它库区借阅不受影响。
 */
@Service
public class OutboundService {

    private final ZoneRepository zoneRepository;
    private final TreatmentChainRepository chainRepository;
    private final OutboundRepository outboundRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public OutboundService(ZoneRepository zoneRepository, TreatmentChainRepository chainRepository,
                           OutboundRepository outboundRepository,
                           IdempotencyService idempotencyService, Clock clock) {
        this.zoneRepository = zoneRepository;
        this.chainRepository = chainRepository;
        this.outboundRepository = outboundRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 登记出库；库区处于处置状态时拒绝（409），其它库区不受影响。
     */
    @Transactional
    public OutboundResult register(OutboundRequest request) {
        if (!zoneRepository.zoneExists(request.zoneCode())) {
            throw ApiException.unprocessable("库区不存在: " + request.zoneCode());
        }
        return idempotencyService.execute(request.requestId(),
                fingerprint(request.requestId(), "outbound", request.zoneCode(), request.item()),
                OutboundResult.class,
                () -> {
                    if (chainRepository.isZoneActive(request.zoneCode())) {
                        throw ApiException.conflict("库区处于处置状态，禁止出库登记: " + request.zoneCode());
                    }
                    Instant now = clock.instant();
                    long id = outboundRepository.insert(request.requestId(), request.zoneCode(),
                            request.item(), now);
                    return new OutboundResult(id, request.zoneCode(), request.item(), now);
                });
    }
}
