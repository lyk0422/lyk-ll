package com.example.starter.slot.web;

import com.example.starter.slot.dto.PublishRequest;
import com.example.starter.slot.dto.PublishResult;
import com.example.starter.slot.dto.ReleaseRequest;
import com.example.starter.slot.dto.ReleaseResult;
import com.example.starter.slot.dto.SlotApplicationRequest;
import com.example.starter.slot.dto.SlotApplicationResult;
import com.example.starter.slot.service.SlotCoordinationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 航班时刻协调 HTTP 入口（骨架，待实现）。
 */
@RestController
@RequestMapping("/api/slots")
public class SlotController {

    private final SlotCoordinationService service;

    public SlotController(SlotCoordinationService service) {
        this.service = service;
    }

    @PostMapping("/applications")
    public SlotApplicationResult apply(@Valid @RequestBody SlotApplicationRequest request) {
        return service.apply(request);
    }

    @PostMapping("/releases")
    public ReleaseResult release(@Valid @RequestBody ReleaseRequest request) {
        return service.release(request);
    }

    @PostMapping("/publications")
    public PublishResult publish(@Valid @RequestBody PublishRequest request) {
        return service.publish(request);
    }
}
