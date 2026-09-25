package com.example.starter.rolling.api;

import com.example.starter.rolling.api.dto.ActualResultRequest;
import com.example.starter.rolling.api.dto.ActualResultView;
import com.example.starter.rolling.api.dto.FreezeRequest;
import com.example.starter.rolling.api.dto.IssueRequest;
import com.example.starter.rolling.api.dto.ScheduleCreateRequest;
import com.example.starter.rolling.api.dto.ScheduleView;
import com.example.starter.rolling.service.RollingScheduleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轧钢排产 HTTP 接口。
 */
@RestController
@RequestMapping("/api/rolling/schedules")
public class RollingScheduleController {

    private final RollingScheduleService service;

    public RollingScheduleController(RollingScheduleService service) {
        this.service = service;
    }

    @PostMapping
    public ScheduleView create(@Valid @RequestBody ScheduleCreateRequest request) {
        return service.createOrRevise(request);
    }

    @PostMapping("/{id}/validate")
    public ScheduleView validate(@PathVariable Long id) {
        return service.validate(id);
    }

    @PostMapping("/{id}/issue")
    public ScheduleView issue(@PathVariable Long id, @Valid @RequestBody IssueRequest request) {
        return service.issue(id, request);
    }

    @PostMapping("/{id}/freeze")
    public ScheduleView freeze(@PathVariable Long id, @Valid @RequestBody FreezeRequest request) {
        return service.freeze(id, request);
    }

    @GetMapping("/{id}")
    public ScheduleView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/actuals")
    public ActualResultView reportActual(@Valid @RequestBody ActualResultRequest request) {
        return service.reportActual(request);
    }
}
