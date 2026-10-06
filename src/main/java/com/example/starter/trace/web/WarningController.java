package com.example.starter.trace.web;

import com.example.starter.trace.dto.WarningResponse;
import com.example.starter.trace.service.WarningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 蜂场预警查询接口。
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningController {

    private final WarningService warningService;

    public WarningController(WarningService warningService) {
        this.warningService = warningService;
    }

    /**
     * 查询全部蜂场预警。
     */
    @GetMapping
    public List<WarningResponse> listWarnings() {
        return warningService.listWarnings();
    }
}
