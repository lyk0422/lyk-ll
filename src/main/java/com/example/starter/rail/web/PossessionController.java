package com.example.starter.rail.web;

import com.example.starter.rail.dto.ConflictResponse;
import com.example.starter.rail.dto.CreateRequestRequest;
import com.example.starter.rail.dto.PublishSnapshotRequest;
import com.example.starter.rail.dto.RequestResponse;
import com.example.starter.rail.dto.SnapshotResponse;
import com.example.starter.rail.dto.TransitionRequest;
import com.example.starter.rail.model.ConflictDetail;
import com.example.starter.rail.service.PossessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 铁路检修封锁窗 REST API。所有时间以 UTC ISO-8601 时刻传输。
 */
@RestController
@RequestMapping("/api/requests")
public class PossessionController {

    private final PossessionService service;

    public PossessionController(PossessionService service) {
        this.service = service;
    }

    /** 提交封锁申请（PENDING）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestResponse create(@Valid @RequestBody CreateRequestRequest request) {
        return service.create(request);
    }

    /** 查询申请当前状态。 */
    @GetMapping("/{requestId}")
    public RequestResponse get(@PathVariable String requestId) {
        return service.get(requestId);
    }

    /**
     * 审批申请：mode=NORMAL（默认）遇冲突返回 409 冲突明细；
     * mode=PREEMPT 为高优先级紧急抢占，无法全部抢占时同样 409 整笔拒绝。
     */
    @PostMapping("/{requestId}/approval")
    public RequestResponse approve(@PathVariable String requestId,
                                   @RequestParam(defaultValue = "NORMAL") String mode) {
        PossessionService.ApprovalMode approvalMode = "PREEMPT".equalsIgnoreCase(mode)
                ? PossessionService.ApprovalMode.PREEMPT
                : PossessionService.ApprovalMode.NORMAL;
        return service.approve(requestId, approvalMode);
    }

    /** 查询若立即审批会产生的冲突，不改变状态。 */
    @GetMapping("/{requestId}/conflicts")
    public ConflictResponse conflicts(@PathVariable String requestId) {
        List<ConflictDetail> conflicts = service.queryConflicts(requestId);
        return new ConflictResponse(requestId, conflicts.isEmpty(), conflicts);
    }

    /** 激活：仅 APPROVED 可激活，expectedVersion 必须匹配。 */
    @PostMapping("/{requestId}/activation")
    public RequestResponse activate(@PathVariable String requestId,
                                    @Valid @RequestBody TransitionRequest body,
                                    @RequestHeader(value = "Idempotency-Key", required = false)
                                    String requestKey) {
        return service.activate(requestId, body, requestKey);
    }

    /** 完成：仅 ACTIVE 可完成（终态）。 */
    @PostMapping("/{requestId}/completion")
    public RequestResponse complete(@PathVariable String requestId,
                                    @Valid @RequestBody TransitionRequest body,
                                    @RequestHeader(value = "Idempotency-Key", required = false)
                                    String requestKey) {
        return service.complete(requestId, body, requestKey);
    }

    /** 取消：PENDING/APPROVED/ACTIVE 可取消（终态）。 */
    @PostMapping("/{requestId}/cancellation")
    public RequestResponse cancel(@PathVariable String requestId,
                                  @Valid @RequestBody TransitionRequest body,
                                  @RequestHeader(value = "Idempotency-Key", required = false)
                                  String requestKey) {
        return service.cancel(requestId, body, requestKey);
    }

    /** 发布不可变计划快照。 */
    @PostMapping("/{requestId}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse publish(@PathVariable String requestId,
                                    @Valid @RequestBody PublishSnapshotRequest body) {
        return service.publishSnapshot(requestId, body.snapshotId());
    }

    /** 读取快照；申请后续状态变化不会改变快照内容。 */
    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotResponse snapshot(@PathVariable String snapshotId) {
        return service.getSnapshot(snapshotId);
    }
}
