package com.example.starter.api;

import com.example.starter.api.dto.ConflictView;
import com.example.starter.api.dto.ConflictsResponse;
import com.example.starter.api.dto.CreateRequestRequest;
import com.example.starter.api.dto.OperationRequest;
import com.example.starter.api.dto.OperationResultView;
import com.example.starter.api.dto.RequestView;
import com.example.starter.api.dto.SnapshotRequest;
import com.example.starter.api.dto.SnapshotView;
import com.example.starter.service.PossessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 铁路检修封锁窗 REST 入口（骨架，实现逐小步填充）。
 */
@RestController
@RequestMapping("/api/possession")
public class PossessionController {

    private final PossessionService service;

    public PossessionController(PossessionService service) {
        this.service = service;
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView create(@Valid @RequestBody CreateRequestRequest request) {
        return service.create(request);
    }

    @GetMapping("/requests/{requestId}")
    public RequestView get(@PathVariable String requestId) {
        return service.get(requestId);
    }

    @GetMapping("/requests/{requestId}/conflicts")
    public ConflictsResponse conflicts(@PathVariable String requestId) {
        List<ConflictView> conflicts = service.checkConflicts(requestId);
        return new ConflictsResponse(requestId, !conflicts.isEmpty(), conflicts);
    }

    @PostMapping("/requests/{requestId}/approve")
    public OperationResultView approve(@PathVariable String requestId,
                                       @Valid @RequestBody OperationRequest request) {
        return service.approve(requestId, request);
    }

    @PostMapping("/requests/{requestId}/activate")
    public OperationResultView activate(@PathVariable String requestId,
                                        @Valid @RequestBody OperationRequest request) {
        return service.activate(requestId, request);
    }

    @PostMapping("/requests/{requestId}/complete")
    public OperationResultView complete(@PathVariable String requestId,
                                        @Valid @RequestBody OperationRequest request) {
        return service.complete(requestId, request);
    }

    @PostMapping("/requests/{requestId}/cancel")
    public OperationResultView cancel(@PathVariable String requestId,
                                      @Valid @RequestBody OperationRequest request) {
        return service.cancel(requestId, request);
    }

    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotView publishSnapshot(@Valid @RequestBody SnapshotRequest request) {
        return service.publishSnapshot(request.businessTime());
    }

    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotView getSnapshot(@PathVariable String snapshotId) {
        return service.getSnapshot(snapshotId);
    }
}
