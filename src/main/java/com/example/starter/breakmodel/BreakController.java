package com.example.starter.breakmodel;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 直播插播端点：开始与结束。
 */
@RestController
@RequestMapping("/api/breaks")
public class BreakController {

    private final BreakService breakService;

    public BreakController(BreakService breakService) {
        this.breakService = breakService;
    }

    @PostMapping("/start")
    public BreakService.BreakStartResponse start(@Valid @RequestBody BreakStartRequest request) {
        return breakService.start(request);
    }

    @PostMapping("/end")
    public BreakService.BreakEndResponse end(@Valid @RequestBody BreakEndRequest request) {
        return breakService.end(request);
    }
}
