package com.example.starter.slam.web;

import com.example.starter.slam.api.EdgeRequest;
import com.example.starter.slam.api.EdgeView;
import com.example.starter.slam.api.KeyFrameRequest;
import com.example.starter.slam.api.KeyFrameView;
import com.example.starter.slam.api.OptimizeRequest;
import com.example.starter.slam.api.ResultView;
import com.example.starter.slam.api.TaskView;
import com.example.starter.slam.service.GraphService;
import com.example.starter.slam.service.OptimizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 位姿图后端 HTTP 入口。
 */
@RestController
@RequestMapping("/api/slam")
public class SlamController {

    private final GraphService graphService;
    private final OptimizationService optimizationService;

    public SlamController(GraphService graphService, OptimizationService optimizationService) {
        this.graphService = graphService;
        this.optimizationService = optimizationService;
    }

    @PutMapping("/graphs/{graphId}/frames/{frameId}")
    public KeyFrameView putFrame(@PathVariable String graphId,
                                 @PathVariable String frameId,
                                 @Valid @RequestBody KeyFrameRequest request) {
        return graphService.putFrame(graphId, frameId, request);
    }

    @PutMapping("/graphs/{graphId}/edges/{edgeId}")
    public EdgeView putEdge(@PathVariable String graphId,
                            @PathVariable String edgeId,
                            @Valid @RequestBody EdgeRequest request) {
        return graphService.putEdge(graphId, edgeId, request);
    }

    @PostMapping("/optimizations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView submit(@Valid @RequestBody OptimizeRequest request) {
        return optimizationService.submit(request);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskView getTask(@PathVariable String taskId) {
        return optimizationService.getTask(taskId);
    }

    @GetMapping("/requests/{requestId}")
    public TaskView getByRequest(@PathVariable String requestId) {
        return optimizationService.getByRequestId(requestId);
    }

    @PostMapping("/tasks/{taskId}/retry")
    public TaskView retry(@PathVariable String taskId) {
        return optimizationService.retry(taskId);
    }

    @GetMapping("/tasks/{taskId}/result")
    public ResultView getResult(@PathVariable String taskId) {
        return optimizationService.getResult(taskId);
    }
}
