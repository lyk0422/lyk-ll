package com.example.starter.dailycheck;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活度计日检接口。
 */
@RestController
@RequestMapping("/api/daily-checks")
public class DailyCheckController {

    private final DailyCheckService service;

    public DailyCheckController(DailyCheckService service) {
        this.service = service;
    }

    /**
     * 日检请求。
     *
     * @param requestId           幂等请求键
     * @param calibratorId        活度计
     * @param referenceSourceId   参考源
     * @param measuredActivityMbq 实测活度，单位 MBq
     * @param checkedAt           日检时刻（UTC）
     */
    public record CheckRequest(@NotBlank String requestId, @NotNull Long calibratorId,
                               @NotNull Long referenceSourceId, @Positive double measuredActivityMbq,
                               @NotNull Instant checkedAt) {
    }

    /**
     * 执行日检；同键同参重放返回首次结果（HTTP 200），新受理返回 HTTP 201。
     */
    @PostMapping
    public ResponseEntity<DailyCheckService.DailyCheckView> check(@Valid @RequestBody CheckRequest request) {
        DailyCheckService.DailyCheckView view = service.check(request.requestId(), request.calibratorId(),
                request.referenceSourceId(), request.measuredActivityMbq(), request.checkedAt());
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }
}
