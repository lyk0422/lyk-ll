package com.example.starter.deicing.api;

import com.example.starter.deicing.service.DeicingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 机坪除冰排队与起飞时刻保护 HTTP 入口（UTC 时刻，epoch 毫秒）。
 */
@RestController
@RequestMapping("/api/deicing")
public class DeicingController {

    private final DeicingService deicingService;

    /**
     * @param deicingService 领域服务
     */
    public DeicingController(DeicingService deicingService) {
        this.deicingService = deicingService;
    }

    /** 申请除冰：首次 201；同键同参重放返回首次 201 结果。 */
    @PostMapping("/requests")
    public ResponseEntity<QueueItemResponse> apply(@Valid @RequestBody ApplyRequest request) {
        DeicingService.IdentifiedResult result = deicingService.apply(request);
        return ResponseEntity.status(result.httpStatus()).body(result.view());
    }

    /** 出队确认。 */
    @PostMapping("/dispatch")
    public ResponseEntity<QueueItemResponse> dispatch(@Valid @RequestBody RequestIdRequest request) {
        DeicingService.IdentifiedResult result = deicingService.dispatch(request.requestId());
        return ResponseEntity.status(result.httpStatus()).body(result.view());
    }

    /** 作业开始。 */
    @PostMapping("/work/start")
    public QueueItemResponse start(@Valid @RequestBody RequestIdRequest request) {
        return deicingService.startWork(request.requestId());
    }

    /** 作业完成。 */
    @PostMapping("/work/complete")
    public QueueItemResponse complete(@Valid @RequestBody RequestIdRequest request) {
        return deicingService.completeWork(request.requestId());
    }

    /** 前序延误回报。 */
    @PostMapping("/delay")
    public QueueItemResponse delay(@Valid @RequestBody DelayRequest request) {
        return deicingService.reportDelay(request);
    }

    /** HOT 风险/失效后确认重新除冰。 */
    @PostMapping("/reschedule")
    public QueueItemResponse reschedule(@Valid @RequestBody RequestIdRequest request) {
        return deicingService.reschedule(request.requestId());
    }

    /** 起飞回报与判定。 */
    @PostMapping("/depart")
    public ResponseEntity<QueueItemResponse> depart(@Valid @RequestBody DepartRequest request) {
        DeicingService.IdentifiedResult result = deicingService.depart(request);
        return ResponseEntity.status(result.httpStatus()).body(result.view());
    }

    /** 取消。 */
    @PostMapping("/cancel")
    public ResponseEntity<QueueItemResponse> cancel(@Valid @RequestBody CancelRequest request) {
        DeicingService.IdentifiedResult result = deicingService.cancel(request);
        return ResponseEntity.status(result.httpStatus()).body(result.view());
    }

    /** 队列快照。 */
    @GetMapping("/snapshot")
    public SnapshotResponse snapshot() {
        return deicingService.snapshot();
    }

    /** 单条记录查询。 */
    @GetMapping("/requests/{requestId}")
    public QueueItemResponse get(@PathVariable String requestId) {
        return deicingService.get(requestId);
    }

    /** 车位/除冰车占用时间线。 */
    @GetMapping("/timeline")
    public List<AssignmentView> timeline(@RequestParam(required = false) String bayId,
                                         @RequestParam(required = false) Long fromTs,
                                         @RequestParam(required = false) Long toTs) {
        return deicingService.timeline(bayId, fromTs, toTs);
    }
}
