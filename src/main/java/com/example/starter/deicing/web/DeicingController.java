package com.example.starter.deicing.web;

import com.example.starter.deicing.service.DeicingService;
import com.example.starter.deicing.db.TimetableRepository;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.CancelRequest;
import com.example.starter.deicing.web.dto.DequeueRequest;
import com.example.starter.deicing.web.dto.MutationResponse;
import com.example.starter.deicing.web.dto.QueueItemView;
import com.example.starter.deicing.web.dto.RescheduleRequest;
import com.example.starter.deicing.web.dto.SnapshotResponse;
import com.example.starter.deicing.web.dto.StartJobRequest;
import com.example.starter.deicing.web.dto.TakeoffRequest;
import com.example.starter.deicing.web.dto.TakeoffResponse;
import com.example.starter.deicing.web.dto.TimelineResponse;
import com.example.starter.deicing.web.dto.TimetableEntryRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 除冰排队服务接口，所有时刻字段使用 UTC（Instant）。
 */
@RestController
@RequestMapping("/api/deicing")
public class DeicingController {

    private final DeicingService deicingService;
    private final TimetableRepository timetableRepository;

    public DeicingController(DeicingService deicingService,
                             TimetableRepository timetableRepository) {
        this.deicingService = deicingService;
        this.timetableRepository = timetableRepository;
    }

    /** 提交除冰申请（requestId 幂等）。 */
    @PostMapping("/applications")
    public ResponseEntity<MutationResponse> apply(@Valid @RequestBody ApplicationRequest request) {
        MutationResponse response = deicingService.apply(request);
        return ResponseEntity.status(response.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /** 出队确认（requestId 幂等），仅队首可出队。 */
    @PostMapping("/dequeue")
    public MutationResponse dequeue(@Valid @RequestBody DequeueRequest request) {
        return deicingService.dequeue(request.requestId());
    }

    /** 作业开始，开始后不可被插队。 */
    @PostMapping("/jobs/start")
    public MutationResponse startJob(@Valid @RequestBody StartJobRequest request) {
        return deicingService.startJob(request.requestId());
    }

    /** 实际起飞回报，判定两步法 HOT 是否有效。 */
    @PostMapping("/takeoffs")
    public TakeoffResponse takeoff(@Valid @RequestBody TakeoffRequest request) {
        return deicingService.reportTakeoff(request.requestId(), request.actualTakeoffTime());
    }

    /** 取消申请（终态幂等）。 */
    @PostMapping("/cancellations")
    public MutationResponse cancel(@Valid @RequestBody CancelRequest request) {
        return deicingService.cancel(request.requestId());
    }

    /** HOT 风险航班重排目标起飞时刻。 */
    @PostMapping("/reschedules")
    public MutationResponse reschedule(@Valid @RequestBody RescheduleRequest request) {
        return deicingService.reschedule(request.requestId(), request.newTargetTakeoffTime());
    }

    /** 合成航班时刻表维护（两步法 HOT 分钟数）。 */
    @PostMapping("/timetable")
    public ResponseEntity<Map<String, Object>> upsertTimetable(
            @Valid @RequestBody TimetableEntryRequest request) {
        timetableRepository.upsertHotMinutes(request.flightNumber(), request.hotMinutes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("flightNumber", request.flightNumber(),
                        "hotMinutes", request.hotMinutes()));
    }

    /** 队列快照，重复查询稳定。 */
    @GetMapping("/queue")
    public SnapshotResponse snapshot() {
        return deicingService.snapshot();
    }

    /** 车位占用时间线（半开区间）。 */
    @GetMapping("/bays/timeline")
    public TimelineResponse timeline() {
        return deicingService.timeline();
    }

    /** 查询单条申请。 */
    @GetMapping("/applications/{requestId}")
    public QueueItemView get(@PathVariable String requestId) {
        return deicingService.get(requestId);
    }
}
