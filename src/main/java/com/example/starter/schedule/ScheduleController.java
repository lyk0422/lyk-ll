package com.example.starter.schedule;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.schedule.ScheduleRequests.CreateSchedule;
import com.example.starter.schedule.ScheduleRequests.RegisterRollChange;
import com.example.starter.schedule.ScheduleRequests.ReportActuals;
import com.example.starter.schedule.ScheduleRequests.ReviseSchedule;
import com.example.starter.schedule.ScheduleResponses.ActualsResult;
import com.example.starter.schedule.ScheduleResponses.FreezeResult;
import com.example.starter.schedule.ScheduleResponses.ScheduleView;
import com.example.starter.schedule.ScheduleResponses.ValidationResult;

/**
 * 轧钢排产与轧辊更换窗口服务 API。
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final IdempotencyService idempotencyService;

    public ScheduleController(ScheduleService scheduleService, IdempotencyService idempotencyService) {
        this.scheduleService = scheduleService;
        this.idempotencyService = idempotencyService;
    }

    /** 编制排产单（幂等：同 requestId 同参返回首次结果，异参 409）。 */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateSchedule request) {
        String fingerprint = Fingerprint.ofSchedule(request.requestId(), request.lineCode(),
                request.unitNo(), request.items(), request.rollChangeWindows());
        IdempotencyService.Outcome outcome = idempotencyService.run(
                request.requestId(), fingerprint, HttpStatus.CREATED.value(),
                () -> scheduleService.create(request));
        return json(outcome);
    }

    /** 排产单详情。 */
    @GetMapping("/{id}")
    public ScheduleView get(@PathVariable long id) {
        return scheduleService.getView(id);
    }

    /** 校验：违例返回 422 与全部明细，整单拒绝。 */
    @PostMapping("/{id}/validate")
    public ValidationResult validate(@PathVariable long id) {
        return scheduleService.validate(id);
    }

    /** 下达：同产线同时刻唯一生效版本，并发唯一成功。 */
    @PostMapping("/{id}/release")
    public ScheduleView release(@PathVariable long id) {
        return scheduleService.release(id);
    }

    /** 下达后修订：生成 version+1 新版本（幂等）。 */
    @PostMapping("/{id}/revise")
    public ResponseEntity<String> revise(@PathVariable long id,
                                         @Valid @RequestBody ReviseSchedule request) {
        String fingerprint = Fingerprint.ofSchedule(request.requestId(), "revise-of-" + id,
                "next", request.items(), request.rollChangeWindows());
        IdempotencyService.Outcome outcome = idempotencyService.run(
                request.requestId(), fingerprint, HttpStatus.CREATED.value(),
                () -> scheduleService.revise(id, request));
        return json(outcome);
    }

    /** 实绩回传（幂等）：按 合同号+道次 去重，达换辊阈值自动锁定。 */
    @PostMapping("/{id}/actuals")
    public ResponseEntity<String> reportActuals(@PathVariable long id,
                                                @Valid @RequestBody ReportActuals request) {
        String fingerprint = Fingerprint.ofActuals(request.requestId(), id, request.actuals());
        IdempotencyService.Outcome outcome = idempotencyService.run(
                request.requestId(), fingerprint, HttpStatus.OK.value(),
                () -> scheduleService.reportActuals(id, request));
        return json(outcome);
    }

    /** 登记换辊：清零本周期实绩里程并解除锁定。 */
    @PostMapping("/{id}/roll-changes")
    public ScheduleView registerRollChange(@PathVariable long id,
                                           @Valid @RequestBody RegisterRollChange request) {
        return scheduleService.registerRollChange(id, request);
    }

    /** 日终冻结（终态）：重复冻结幂等成功。 */
    @PostMapping("/{id}/freeze")
    public FreezeResult freeze(@PathVariable long id) {
        return scheduleService.freeze(id);
    }

    private ResponseEntity<String> json(IdempotencyService.Outcome outcome) {
        return ResponseEntity.status(outcome.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.bodyJson());
    }
}
