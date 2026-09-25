package com.example.starter.roaming.api;

import com.example.starter.roaming.service.AuthorizationService;
import com.example.starter.roaming.service.ChargingSessionService;
import com.example.starter.roaming.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 充电漫游授权与跨网清分 HTTP 入口。
 */
@RestController
@RequestMapping("/api/roaming")
public class RoamingController {

    private final AuthorizationService authorizationService;
    private final ChargingSessionService chargingSessionService;
    private final SettlementService settlementService;

    public RoamingController(AuthorizationService authorizationService,
                             ChargingSessionService chargingSessionService,
                             SettlementService settlementService) {
        this.authorizationService = authorizationService;
        this.chargingSessionService = chargingSessionService;
        this.settlementService = settlementService;
    }

    /** 即插即充授权。 */
    @PostMapping("/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
        return authorizationService.authorize(request);
    }

    /** 会话启动。 */
    @PostMapping("/sessions/start")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse start(@Valid @RequestBody StartSessionRequest request) {
        return chargingSessionService.start(request);
    }

    /** 会话结束。 */
    @PostMapping("/sessions/{sessionNo}/end")
    public SessionResponse end(@PathVariable String sessionNo,
                               @Valid @RequestBody EndSessionRequest request) {
        return chargingSessionService.end(sessionNo, request);
    }

    /** 计量快照上传。 */
    @PostMapping("/sessions/{sessionNo}/meter-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public MeterSnapshotResponse meter(@PathVariable String sessionNo,
                                       @Valid @RequestBody MeterSnapshotRequest request) {
        return chargingSessionService.meter(sessionNo, request);
    }

    /** 清分日封存。 */
    @PostMapping("/settlement/batches/seal")
    public SettlementBatchResponse seal(@Valid @RequestBody SealBatchRequest request) {
        return settlementService.seal(request);
    }

    /** 挂账人工核销。 */
    @PostMapping("/settlement/suspenses/{suspenseNo}/write-off")
    public WriteOffResponse writeOff(@PathVariable String suspenseNo,
                                     @Valid @RequestBody WriteOffRequest request) {
        return settlementService.writeOff(suspenseNo, request);
    }
}
