package com.example.starter.tick;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调度 tick 与结算水位线端点。
 */
@RestController
@RequestMapping("/api")
public class TickController {

    /**
     * 水位线推进请求。
     */
    public record WatermarkRequest(@NotNull Long watermarkMs) {
    }

    private final TickService tickService;

    public TickController(TickService tickService) {
        this.tickService = tickService;
    }

    @PostMapping("/ticks")
    public TickService.TickResponse tick(@Valid @RequestBody TickRequest request) {
        return tickService.tick(request);
    }

    @PostMapping("/channels/{channelId}/watermark")
    public TickService.WatermarkResponse advanceWatermark(@PathVariable String channelId,
                                                          @Valid @RequestBody WatermarkRequest request) {
        return tickService.advanceWatermark(channelId, request.watermarkMs());
    }
}
