package com.example.starter.irrigation.api;

import com.example.starter.irrigation.api.MeteringDtos.MeterReportRequest;
import com.example.starter.irrigation.api.MeteringDtos.MeterSnapshotResponse;
import com.example.starter.irrigation.service.MeteringService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实供计量上报接口。
 */
@RestController
@RequestMapping("/api/irrigation/meterings")
public class MeteringController {

    private final MeteringService meteringService;

    public MeteringController(MeteringService meteringService) {
        this.meteringService = meteringService;
    }

    /** 上报计量：重复 eventId 不重复累计；封存后到达只登记迟到事件。 */
    @PostMapping("/report")
    @ResponseStatus(HttpStatus.OK)
    public MeterSnapshotResponse report(@Valid @RequestBody MeterReportRequest request) {
        return meteringService.report(request);
    }
}
