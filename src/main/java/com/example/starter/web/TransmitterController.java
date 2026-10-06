package com.example.starter.web;

import com.example.starter.service.DirectiveService;
import com.example.starter.web.dto.LiftRequest;
import com.example.starter.web.dto.LiftResponse;
import com.example.starter.web.dto.PowerView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发射机功率指令入口：解除限功率与功率轨迹查询。
 */
@RestController
@RequestMapping("/api/transmitters")
public class TransmitterController {

    private final DirectiveService directiveService;

    public TransmitterController(DirectiveService directiveService) {
        this.directiveService = directiveService;
    }

    @PostMapping("/{transmitterId}/lift")
    public LiftResponse lift(@PathVariable long transmitterId, @Valid @RequestBody LiftRequest request) {
        return directiveService.lift(transmitterId, request);
    }

    @GetMapping("/{transmitterId}/power")
    public PowerView power(@PathVariable long transmitterId) {
        return directiveService.powerView(transmitterId);
    }
}
