package com.example.starter.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.DirectiveService;
import com.example.starter.service.ReleaseService;
import com.example.starter.web.dto.DirectiveResponse;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;

/**
 * 发射机维度入口：限功率解除（双人确认 + 复测原子提交）、指令轨迹查询。
 */
@RestController
@RequestMapping("/api/transmitters")
public class TransmitterController {

    private final ReleaseService releaseService;
    private final DirectiveService directiveService;

    public TransmitterController(ReleaseService releaseService, DirectiveService directiveService) {
        this.releaseService = releaseService;
        this.directiveService = directiveService;
    }

    @PostMapping("/{transmitterId}/release")
    public ReleaseResponse release(@PathVariable long transmitterId,
            @Valid @RequestBody ReleaseRequest request) {
        return releaseService.release(transmitterId, request);
    }

    @GetMapping("/{transmitterId}/directives")
    public List<DirectiveResponse> directives(@PathVariable long transmitterId) {
        return directiveService.listByTransmitter(transmitterId);
    }
}
