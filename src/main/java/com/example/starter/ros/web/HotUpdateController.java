package com.example.starter.ros.web;

import com.example.starter.ros.dto.HotUpdateRequest;
import com.example.starter.ros.dto.HotUpdateResponse;
import com.example.starter.ros.service.HotUpdateService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 事务式热更新入口。 */
@RestController
@RequestMapping("/api/ros")
public class HotUpdateController {

    private final HotUpdateService hotUpdateService;

    public HotUpdateController(HotUpdateService hotUpdateService) {
        this.hotUpdateService = hotUpdateService;
    }

    @PostMapping("/transactions/hot-update")
    public ResponseEntity<HotUpdateResponse> update(@Valid @RequestBody HotUpdateRequest request) {
        return ResponseEntity.ok(hotUpdateService.update(request));
    }
}
