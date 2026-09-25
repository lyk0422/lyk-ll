package com.example.starter.transition.web;

import com.example.starter.transition.api.ApplyTransitionRequest;
import com.example.starter.transition.api.AppealRequest;
import com.example.starter.transition.api.ArchiveRequest;
import com.example.starter.transition.api.ExecuteTransitionRequest;
import com.example.starter.transition.api.GradeUpsertRequest;
import com.example.starter.transition.api.InspectionRequest;
import com.example.starter.transition.api.RegisterRollRequest;
import com.example.starter.transition.api.Responses.GradeResponse;
import com.example.starter.transition.api.Responses.InspectionResponse;
import com.example.starter.transition.api.Responses.JudgementResponse;
import com.example.starter.transition.api.Responses.OrderDetailResponse;
import com.example.starter.transition.api.Responses.OrderResponse;
import com.example.starter.transition.api.Responses.RollDetailResponse;
import com.example.starter.transition.api.Responses.RollResponse;
import com.example.starter.transition.domain.BizType;
import com.example.starter.transition.po.TransitionOrder;
import com.example.starter.transition.po.TransitionRoll;
import com.example.starter.transition.service.GradeService;
import com.example.starter.transition.service.IdempotencyService;
import com.example.starter.transition.service.IdempotencyService.IdempotentResponse;
import com.example.starter.transition.service.RollService;
import com.example.starter.transition.service.RollService.InspectionOutcome;
import com.example.starter.transition.service.TransitionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

/**
 * 改品种过渡料控制 API：申请→方式判定→执行→过渡料处置→完成→归档；
 * 申请与检验提交等写操作均以 requestId 幂等。
 */
@RestController
@RequestMapping("/api")
public class TransitionController {

    private final GradeService gradeService;
    private final TransitionService transitionService;
    private final RollService rollService;
    private final IdempotencyService idempotencyService;

    public TransitionController(GradeService gradeService, TransitionService transitionService,
                                RollService rollService, IdempotencyService idempotencyService) {
        this.gradeService = gradeService;
        this.transitionService = transitionService;
        this.rollService = rollService;
        this.idempotencyService = idempotencyService;
    }

    /** 登记/更新合成品种及工艺窗口。 */
    @PostMapping("/grades")
    public ResponseEntity<String> upsertGrade(@Valid @RequestBody GradeUpsertRequest request) {
        return idempotent(BizType.GRADE_UPSERT, request.requestId(),
                IdempotencyService.fingerprint(request.requestId(), BizType.GRADE_UPSERT.name(),
                        request.gradeCode(), request.basisWeight(), request.ashLevel(),
                        request.speedMin(), request.speedMax(),
                        request.consistencyMin(), request.consistencyMax(), request.tensileMin()),
                () -> GradeResponse.from(gradeService.upsert(request)));
    }

    @GetMapping("/grades")
    public List<GradeResponse> listGrades() {
        return gradeService.list().stream().map(GradeResponse::from).toList();
    }

    /** 申请改品种（含方式判定）；同一纸机并发申请唯一受理。 */
    @PostMapping("/transitions/apply")
    public ResponseEntity<String> apply(@Valid @RequestBody ApplyTransitionRequest request) {
        return idempotent(BizType.APPLY, request.requestId(),
                IdempotencyService.fingerprint(request.requestId(), BizType.APPLY.name(),
                        request.machineId(), request.orderNo(),
                        request.fromGradeCode(), request.toGradeCode()),
                () -> OrderResponse.from(transitionService.apply(request)));
    }

    /** 执行改品种：关键参数窗口校验 + 资源占用同事务登记。 */
    @PostMapping("/transitions/execute")
    public ResponseEntity<String> execute(@Valid @RequestBody ExecuteTransitionRequest request) {
        return idempotent(BizType.EXECUTE, request.requestId(),
                IdempotencyService.fingerprint(request.requestId(), BizType.EXECUTE.name(),
                        request.orderNo(), request.switchSeconds(),
                        request.machineSpeed(), request.consistency()),
                () -> OrderResponse.from(transitionService.execute(request)));
    }

    /** 过渡料处置完成。 */
    @PostMapping("/transitions/{orderNo}/dispose")
    public OrderResponse dispose(@PathVariable String orderNo) {
        return OrderResponse.from(transitionService.dispose(orderNo));
    }

    /** 完成改品种（释放纸机占用）。 */
    @PostMapping("/transitions/{orderNo}/complete")
    public OrderResponse complete(@PathVariable String orderNo) {
        return OrderResponse.from(transitionService.complete(orderNo));
    }

    /** 归档（终态）；重复归档返回幂等成功。 */
    @PostMapping("/transitions/archive")
    public ResponseEntity<String> archive(@Valid @RequestBody ArchiveRequest request) {
        return idempotent(BizType.ARCHIVE, request.requestId(),
                IdempotencyService.fingerprint(request.requestId(), BizType.ARCHIVE.name(),
                        request.orderNo()),
                () -> OrderResponse.from(transitionService.archive(request.orderNo())));
    }

    /** 登记过渡纸卷（卷号唯一，强制降级）。 */
    @PostMapping("/rolls/register")
    public ResponseEntity<String> registerRoll(@Valid @RequestBody RegisterRollRequest request) {
        return idempotent(BizType.REGISTER_ROLL, request.requestId(),
                IdempotencyService.fingerprint(request.requestId(), BizType.REGISTER_ROLL.name(),
                        request.rollNo(), request.orderNo(), request.lengthM()),
                () -> RollResponse.from(rollService.register(request)));
    }

    /** 提交过渡卷检验数据；归档后迟到只记迟到事件。 */
    @PostMapping("/rolls/inspect")
    public ResponseEntity<String> inspect(@Valid @RequestBody InspectionRequest request) {
        return idempotent(BizType.INSPECTION, request.requestId(),
                IdempotencyService.fingerprint(request.requestId(), BizType.INSPECTION.name(),
                        request.rollNo(), request.measuredBasisWeight(),
                        request.measuredAshLevel(), request.measuredTensile()),
                () -> toInspectionResponse(rollService.inspect(request)));
    }

    /** 复议改判正品（仅一次）。 */
    @PostMapping("/rolls/appeal")
    public ResponseEntity<String> appeal(@Valid @RequestBody AppealRequest request) {
        return idempotent(BizType.APPEAL, request.requestId(),
                IdempotencyService.fingerprint(request.requestId(), BizType.APPEAL.name(),
                        request.rollNo()),
                () -> RollResponse.from(rollService.appeal(request.requestId(), request.rollNo())));
    }

    /** 改品种单详情：过渡料长度复算、方式判定复核、降级卷明细。 */
    @GetMapping("/transitions/{orderNo}")
    public OrderDetailResponse orderDetail(@PathVariable String orderNo) {
        TransitionOrder order = transitionService.require(orderNo);
        List<RollResponse> rolls = rollService.rollsOfOrder(orderNo).stream()
                .map(RollResponse::from).toList();
        return new OrderDetailResponse(
                OrderResponse.from(order),
                order.machineSpeed == null || order.switchSeconds == null ? null
                        : TransitionService.computeTransitionLength(order.machineSpeed, order.switchSeconds),
                TransitionService.decideSwitchMode(order.basisWeightFrom, order.basisWeightTo).name(),
                Math.abs(order.ashTo - order.ashFrom) > 2,
                rolls,
                rollService.lateEventCount(orderNo));
    }

    /** 过渡卷详情：卷状态与完整改判链。 */
    @GetMapping("/rolls/{rollNo}")
    public RollDetailResponse rollDetail(@PathVariable String rollNo) {
        TransitionRoll roll = rollService.requireRoll(rollNo);
        List<JudgementResponse> chain = rollService.judgementChain(rollNo).stream()
                .map(JudgementResponse::from).toList();
        return new RollDetailResponse(RollResponse.from(roll), chain);
    }

    private InspectionResponse toInspectionResponse(InspectionOutcome outcome) {
        JudgementResponse first = outcome.firstJudgement() == null ? null
                : JudgementResponse.from(outcome.firstJudgement());
        TransitionRoll roll = outcome.roll();
        return new InspectionResponse(roll.rollNo, outcome.late(),
                outcome.late() ? "LATE" : roll.inspectionStatus,
                roll.gatePassed, roll.finalGrade, first);
    }

    private ResponseEntity<String> idempotent(BizType bizType, String requestId,
                                              String fingerprint, Supplier<Object> action) {
        IdempotentResponse response = idempotencyService.execute(bizType, requestId, fingerprint, action);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotent-Replay", String.valueOf(response.replayed()))
                .body(response.body());
    }
}
