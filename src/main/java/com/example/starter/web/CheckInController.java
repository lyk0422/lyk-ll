package com.example.starter.web;

import com.example.starter.service.CheckInService;
import com.example.starter.web.dto.CheckInRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生上下车打卡入口。
 */
@RestController
@RequestMapping("/api/check-ins")
public class CheckInController {

    private final CheckInService checkInService;

    public CheckInController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    /**
     * 打卡；按学生与日期去重，重复打卡返回首次记录。
     */
    @PostMapping
    public CheckInRequest.Result checkIn(@Valid @RequestBody CheckInRequest request) {
        return checkInService.checkIn(request);
    }
}
