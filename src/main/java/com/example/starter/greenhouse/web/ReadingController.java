package com.example.starter.greenhouse.web;

import com.example.starter.greenhouse.dto.AlertAckRequest;
import com.example.starter.greenhouse.dto.AlertAckResponse;
import com.example.starter.greenhouse.dto.ReadingIngestRequest;
import com.example.starter.greenhouse.dto.ReadingResponse;
import com.example.starter.greenhouse.service.AlertService;
import com.example.starter.greenhouse.service.ReadingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 传感器读数上报与偏差告警确认接口。
 */
@RestController
@RequestMapping("/api/greenhouse")
public class ReadingController {

    private final ReadingService readingService;
    private final AlertService alertService;

    public ReadingController(ReadingService readingService, AlertService alertService) {
        this.readingService = readingService;
        this.alertService = alertService;
    }

    /** 读数上报：去重归位，连续 3 点偏离触发告警。 */
    @PostMapping("/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse ingest(@Valid @RequestBody ReadingIngestRequest req) {
        return readingService.ingest(req);
    }

    /** 告警确认：requestId 幂等，同键异参 409。 */
    @PostMapping("/alerts/acknowledge")
    public AlertAckResponse acknowledge(@Valid @RequestBody AlertAckRequest req) {
        return alertService.acknowledge(req);
    }
}
