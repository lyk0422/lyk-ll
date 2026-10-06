package com.example.starter.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.IdempotencyService;
import com.example.starter.service.PlanService;
import com.example.starter.web.dto.ActionRequest;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.ArrangeRequest;
import com.example.starter.web.dto.CancelRequest;
import com.example.starter.web.dto.PlanResponse;
import com.example.starter.web.dto.RescheduleRequest;

import jakarta.validation.Valid;

/**
 * 引航计划接口：申请、编排、确认、执行、核销、取消、改期；全部写操作按 requestId 幂等。
 */
@RestController
@RequestMapping("/api/pilot-plans")
@Validated
public class PlanController {

    private final PlanService planService;
    private final IdempotencyService idempotencyService;

    public PlanController(PlanService planService, IdempotencyService idempotencyService) {
        this.planService = planService;
        this.idempotencyService = idempotencyService;
    }

    /** 申请引航计划。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse apply(@Valid @RequestBody ApplyRequest request) {
        return idempotencyService.execute("APPLY", request.requestId(),
                List.of(request.planNo(), request.shipName(), request.voyageNo()),
                () -> planService.apply(request.planNo(), request.shipName(), request.voyageNo()));
    }

    /** 编排：绑定引航员、拖轮组合与潮水窗口。 */
    @PostMapping("/{id}/arrange")
    public PlanResponse arrange(@PathVariable long id, @Valid @RequestBody ArrangeRequest request) {
        List<String> tugFields = request.tugboatIds().stream().sorted().map(String::valueOf).toList();
        List<String> fields = new java.util.ArrayList<>(List.of(
                String.valueOf(id), String.valueOf(request.pilotId()),
                request.tideStart().toString(), request.tideEnd().toString()));
        fields.addAll(tugFields);
        return idempotencyService.execute("ARRANGE", request.requestId(), fields,
                () -> planService.arrange(id, request.pilotId(), request.tugboatIds(),
                        request.tideStart(), request.tideEnd()));
    }

    /** 确认：同事务锁定引航员与拖轮。 */
    @PostMapping("/{id}/confirm")
    public PlanResponse confirm(@PathVariable long id, @Valid @RequestBody ActionRequest request) {
        return idempotencyService.execute("CONFIRM", request.requestId(), List.of(String.valueOf(id)),
                () -> planService.confirm(id));
    }

    /** 开始执行。 */
    @PostMapping("/{id}/start")
    public PlanResponse start(@PathVariable long id, @Valid @RequestBody ActionRequest request) {
        return idempotencyService.execute("START", request.requestId(), List.of(String.valueOf(id)),
                () -> planService.start(id));
    }

    /** 核销。 */
    @PostMapping("/{id}/settle")
    public PlanResponse settle(@PathVariable long id, @Valid @RequestBody ActionRequest request) {
        return idempotencyService.execute("SETTLE", request.requestId(), List.of(String.valueOf(id)),
                () -> planService.settle(id));
    }

    /** 取消：执行前 2 小时外免费，不足收取合成违约记录。 */
    @PostMapping("/{id}/cancel")
    public PlanResponse cancel(@PathVariable long id, @Valid @RequestBody CancelRequest request) {
        return idempotencyService.execute("CANCEL", request.requestId(),
                List.of(String.valueOf(id), String.valueOf(request.reason())),
                () -> planService.cancel(id, request.reason()));
    }

    /** 改期：重新校验工时与占用，结论与重新锁定同事务。 */
    @PostMapping("/{id}/reschedule")
    public PlanResponse reschedule(@PathVariable long id, @Valid @RequestBody RescheduleRequest request) {
        return idempotencyService.execute("RESCHEDULE", request.requestId(),
                List.of(String.valueOf(id), request.tideStart().toString(), request.tideEnd().toString()),
                () -> planService.reschedule(id, request.tideStart(), request.tideEnd()));
    }

    /** 查询单个计划。 */
    @GetMapping("/{id}")
    public PlanResponse get(@PathVariable long id) {
        return planService.get(id);
    }

    /** 查询全部计划。 */
    @GetMapping
    public List<PlanResponse> list() {
        return planService.list();
    }
}
