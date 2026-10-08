package com.example.starter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.ManifestQueryService;

/**
 * 联单查询入口：完整去向版本链与迟到回执证据链。
 */
@RestController
@RequestMapping("/api/manifests")
public class ManifestQueryController {

    private final ManifestQueryService manifestQueryService;

    public ManifestQueryController(ManifestQueryService manifestQueryService) {
        this.manifestQueryService = manifestQueryService;
    }

    @GetMapping("/{manifestId}")
    public ManifestQueryService.ManifestDetail detail(@PathVariable long manifestId) {
        return manifestQueryService.detail(manifestId);
    }
}
