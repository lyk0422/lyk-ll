package com.example.starter.flood.web;

import com.example.starter.flood.service.WarningService;
import com.example.starter.flood.web.dto.WarningEventView;
import com.example.starter.flood.web.dto.WarningRequest;
import com.example.starter.flood.web.dto.WarningView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 降雨预警/险情处置链入口。
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningController {

    private final WarningService warningService;

    public WarningController(WarningService warningService) {
        this.warningService = warningService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WarningView open(@Valid @RequestBody WarningRequest request) {
        return warningService.open(request);
    }

    /**
     * 处置链事件流（预警级别提升等留痕）。
     */
    @GetMapping("/{warningNo}/events")
    public java.util.List<WarningEventView> events(@PathVariable String warningNo) {
        return warningService.listEvents(warningNo);
    }
}
