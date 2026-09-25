package com.example.starter.api;

import com.example.starter.api.dto.ReadingDtos;
import com.example.starter.service.ReadingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 传感器读数上报：分区+采样时刻去重，乱序按时刻归位，连续 3 点偏离触发告警。
 */
@RestController
@RequestMapping("/api/readings")
public class ReadingController {

    private final ReadingService readingService;

    public ReadingController(ReadingService readingService) {
        this.readingService = readingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReadingDtos.ReadingResponse ingest(@Valid @RequestBody ReadingDtos.ReadingRequest request) {
        return readingService.ingest(request);
    }
}
