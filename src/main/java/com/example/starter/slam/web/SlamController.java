package com.example.starter.slam.web;

import com.example.starter.slam.domain.OptimizationResult;
import com.example.starter.slam.domain.OptimizationTask;
import com.example.starter.slam.service.GraphWriteService;
import com.example.starter.slam.service.OptimizationService;
import com.example.starter.slam.web.dto.EdgeRequest;
import com.example.starter.slam.web.dto.EdgeResponse;
import com.example.starter.slam.web.dto.KeyframeRequest;
import com.example.starter.slam.web.dto.KeyframeResponse;
import com.example.starter.slam.web.dto.OptimizeRequest;
import com.example.starter.slam.web.dto.TaskResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SLAM 位姿图后端 REST 入口。
 */
@RestController
@RequestMapping("/api/slam")
public class SlamController {

    private final GraphWriteService graphWriteService;
    private final OptimizationService optimizationService;

    public SlamController(GraphWriteService graphWriteService, OptimizationService optimizationService) {
        this.graphWriteService = graphWriteService;
        this.optimizationService = optimizationService;
    }

    @PostMapping("/keyframes")
    public ResponseEntity<KeyframeResponse> writeKeyframe(@Valid @RequestBody KeyframeRequest request) {
        GraphWriteService.KeyframeWriteResult result = graphWriteService.writeKeyframe(request);
        return ResponseEntity.status(result.deduped() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(KeyframeResponse.of(result.keyframe(), result.deduped()));
    }

    @PostMapping("/edges")
    public ResponseEntity<EdgeResponse> writeEdge(@Valid @RequestBody EdgeRequest request) {
        GraphWriteService.EdgeWriteResult result = graphWriteService.writeEdge(request);
        return ResponseEntity.status(result.deduped() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(EdgeResponse.of(result.edge(), result.deduped()));
    }

    @PostMapping("/optimizations")
    public ResponseEntity<TaskResponse> submit(@Valid @RequestBody OptimizeRequest request) {
        OptimizationTask task = optimizationService.submit(request.requestId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(TaskResponse.of(task, optimizationService.findResultForTask(task)));
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<TaskResponse> getByRequest(@PathVariable String requestId) {
        OptimizationTask task = optimizationService.getByRequestId(requestId);
        return ResponseEntity.ok(TaskResponse.of(task, optimizationService.findResultForTask(task)));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable long taskId) {
        OptimizationTask task = optimizationService.getTask(taskId);
        return ResponseEntity.ok(TaskResponse.of(task, optimizationService.findResultForTask(task)));
    }

    @GetMapping("/tasks/{taskId}/result")
    public ResponseEntity<OptimizationResult> getResult(@PathVariable long taskId) {
        return ResponseEntity.ok(optimizationService.getResult(taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ResponseEntity<TaskResponse> retry(@PathVariable long taskId) {
        OptimizationTask task = optimizationService.retry(taskId);
        return ResponseEntity.accepted().body(TaskResponse.of(task, optimizationService.findResultForTask(task)));
    }
}
