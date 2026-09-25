package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.AlertResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 产地预警台账接口：查询与复算。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 查询产地预警台账。
     */
    @GetMapping
    public List<AlertResponse> list() {
        return alertService.list();
    }

    /**
     * 按快检记录复算预警台账。
     */
    @PostMapping("/recompute")
    public List<AlertResponse> recompute() {
        return alertService.recompute();
    }
}
