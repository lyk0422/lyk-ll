package com.example.starter.api;

import com.example.starter.api.dto.WindReadingRequest;
import com.example.starter.service.IdempotencyService;
import com.example.starter.service.WindService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风速数据接入接口。
 */
@RestController
public class WindController {

    private final WindService windService;

    public WindController(WindService windService) {
        this.windService = windService;
    }

    /**
     * 上报分段风速数据；按 requestId 幂等，同分段同时刻去重。
     */
    @PostMapping("/api/lines/{lineId}/wind-readings")
    public ResponseEntity<String> ingest(@PathVariable long lineId,
                                         @Valid @RequestBody WindReadingRequest request) {
        IdempotencyService.Result result = windService.ingest(lineId, request);
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
