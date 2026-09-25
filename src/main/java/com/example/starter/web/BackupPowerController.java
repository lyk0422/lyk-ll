package com.example.starter.web;

import com.example.starter.domain.BaseStation;
import com.example.starter.domain.Generator;
import com.example.starter.service.BackupPowerService;
import com.example.starter.service.IdempotencyService;
import com.example.starter.web.dto.AlertRequest;
import com.example.starter.web.dto.AlertResponse;
import com.example.starter.web.dto.ClosureRequest;
import com.example.starter.web.dto.ClosureResponse;
import com.example.starter.web.dto.ConfirmationRequest;
import com.example.starter.web.dto.ConfirmationResponse;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.GeneratorRequest;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingResponse;
import com.example.starter.web.dto.ReassignmentRequest;
import com.example.starter.web.dto.ReassignmentResponse;
import com.example.starter.web.dto.RestorationRequest;
import com.example.starter.web.dto.RestorationResponse;
import com.example.starter.web.dto.StationRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基站备电调度 REST 入口。告警、抄表、调度、确认、恢复、结案均以 requestId 幂等。
 */
@RestController
@RequestMapping("/api/backup-power")
public class BackupPowerController {

    private static final String TYPE_ALERT = "ALERT";
    private static final String TYPE_READING = "READING";
    private static final String TYPE_DISPATCH = "DISPATCH";
    private static final String TYPE_CONFIRM = "CONFIRM";
    private static final String TYPE_REASSIGN = "REASSIGN";
    private static final String TYPE_RESTORE = "RESTORE";
    private static final String TYPE_CLOSE = "CLOSE";

    private final BackupPowerService service;
    private final IdempotencyService idempotency;

    public BackupPowerController(BackupPowerService service,
                                 IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    /** 创建基站（合成主数据）。 */
    @PostMapping("/stations")
    public BaseStation createStation(@Valid @RequestBody StationRequest request) {
        return service.createStation(request);
    }

    /** 创建油机（合成主数据）。 */
    @PostMapping("/generators")
    public Generator createGenerator(@Valid @RequestBody GeneratorRequest request) {
        return service.createGenerator(request);
    }

    /** 市电中断告警：重复告警合并，不重复派单。 */
    @PostMapping("/alerts")
    public ResponseEntity<?> alert(@Valid @RequestBody AlertRequest request) {
        return runIdempotent(request.requestId(), TYPE_ALERT, request,
                () -> service.handleAlert(request));
    }

    /** 电池容量抄表：去重 + 乱序归位。 */
    @PostMapping("/readings")
    public ResponseEntity<?> reading(@Valid @RequestBody ReadingRequest request) {
        return runIdempotent(request.requestId(), TYPE_READING, request,
                () -> service.handleReading(request));
    }

    /** 生成调度单：低于 2 小时才派单，油机唯一分配。 */
    @PostMapping("/dispatches")
    public ResponseEntity<?> dispatch(@Valid @RequestBody DispatchRequest request) {
        return runIdempotent(request.requestId(), TYPE_DISPATCH, request,
                () -> service.handleDispatch(request));
    }

    /** 油机到位启动后回传发电确认；恰满 30 分钟判超时升级异常。 */
    @PostMapping("/confirmations")
    public ResponseEntity<?> confirm(@Valid @RequestBody ConfirmationRequest request) {
        return runIdempotent(request.requestId(), TYPE_CONFIRM, request,
                () -> service.handleConfirmation(request));
    }

    /** 发电异常后改派另一台油机（与释放原油机同事务）。 */
    @PostMapping("/reassignments")
    public ResponseEntity<?> reassign(@Valid @RequestBody ReassignmentRequest request) {
        return runIdempotent(request.requestId(), TYPE_REASSIGN, request,
                () -> service.handleReassignment(request));
    }

    /** 市电恢复：电池回充、活动调度单解除、释放油机。 */
    @PostMapping("/restorations")
    public ResponseEntity<?> restore(@Valid @RequestBody RestorationRequest request) {
        return runIdempotent(request.requestId(), TYPE_RESTORE, request,
                () -> service.handleRestoration(request));
    }

    /** 调度单结案：终态，重复结案幂等成功。 */
    @PostMapping("/closures")
    public ResponseEntity<?> close(@Valid @RequestBody ClosureRequest request) {
        return runIdempotent(request.requestId(), TYPE_CLOSE, request,
                () -> service.handleClosure(request));
    }

    /**
     * 以 requestId 执行幂等业务：同键同参重放首次响应（含状态码），同键异参 409。
     * 首次业务失败重放时由幂等服务重新抛出相同状态码的 ApiException，交统一异常处理。
     */
    private ResponseEntity<?> runIdempotent(String requestId, String type, Object request,
                                            java.util.function.Supplier<Object> action) {
        IdempotencyService.Outcome outcome =
                idempotency.execute(requestId, type, request, action::get);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.status());
        if (outcome.replayed()) {
            builder.header("X-Idempotent-Replay", "true");
        }
        return builder.body(outcome.body());
    }
}
