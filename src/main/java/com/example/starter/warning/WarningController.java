package com.example.starter.warning;

import com.example.starter.warning.WarningDtos.LiftResponse;
import com.example.starter.warning.WarningDtos.LiftWarningRequest;
import com.example.starter.warning.WarningDtos.PublishWarningRequest;
import com.example.starter.warning.WarningDtos.WarningResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大风预警接口。
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningController {

    private final WarningService warningService;

    public WarningController(WarningService warningService) {
        this.warningService = warningService;
    }

    @PostMapping
    public WarningResponse publish(@Valid @RequestBody PublishWarningRequest request) {
        return warningService.publish(request);
    }

    @PostMapping("/{warningNo}/lift")
    public LiftResponse lift(@PathVariable String warningNo,
            @Valid @RequestBody LiftWarningRequest request) {
        return warningService.lift(warningNo, request);
    }
}
