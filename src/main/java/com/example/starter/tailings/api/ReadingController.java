package com.example.starter.tailings.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.tailings.domain.ReadingType;
import com.example.starter.tailings.repo.ReadingRepository;
import com.example.starter.tailings.service.ReadingService;

/**
 * 监测读数：按测点+采样时刻去重，乱序按时刻归位；归档后迟到读数只记事件。
 */
@RestController
@RequestMapping("/api/readings")
public class ReadingController {

    private final ReadingService readingService;

    public ReadingController(ReadingService readingService) {
        this.readingService = readingService;
    }

    /**
     * 读数提交请求。sampleTime 为采样时刻(Asia/Shanghai)，格式 ISO_LOCAL_DATE_TIME。
     */
    public record ReadingBody(@NotNull Long pondId,
                              @NotBlank String pointCode,
                              @NotNull ReadingType readingType,
                              @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime sampleTime,
                              @NotNull BigDecimal value) {
    }

    @PostMapping
    public ReadingService.ReadingResult submit(@Valid @RequestBody ReadingBody body) {
        return readingService.submit(new ReadingService.ReadingRequest(
                body.pondId(), body.pointCode(), body.readingType(), body.sampleTime(), body.value()));
    }

    @GetMapping
    public List<ReadingService.ReadingView> list(@RequestParam long pondId,
                                                 @RequestParam String pointCode,
                                                 @RequestParam ReadingType readingType) {
        return readingService.list(pondId, pointCode, readingType);
    }

    @GetMapping("/late-events/{pondId}")
    public List<ReadingRepository.LateEventRow> lateEvents(@PathVariable long pondId) {
        return readingService.lateEvents(pondId);
    }
}
