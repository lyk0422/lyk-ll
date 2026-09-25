package com.example.starter.metro;

import java.time.LocalDate;

import com.example.starter.metro.dto.BatchViewResponse;
import com.example.starter.metro.dto.EntryRequest;
import com.example.starter.metro.dto.EntryResponse;
import com.example.starter.metro.dto.EstimateRequest;
import com.example.starter.metro.dto.EstimateResponse;
import com.example.starter.metro.dto.ExitRequest;
import com.example.starter.metro.dto.ExitResponse;
import com.example.starter.metro.dto.SealRequest;
import com.example.starter.metro.dto.SealResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轨交票务清分 API。
 */
@RestController
@RequestMapping("/api/metro")
public class MetroController {

    private final MetroService metroService;

    public MetroController(MetroService metroService) {
        this.metroService = metroService;
    }

    /** 进闸。 */
    @PostMapping("/entry")
    public EntryResponse entry(@Valid @RequestBody EntryRequest request) {
        return metroService.entry(request);
    }

    /** 出闸（含暂记补登）。 */
    @PostMapping("/exit")
    public ExitResponse exit(@Valid @RequestBody ExitRequest request) {
        return metroService.exit(request);
    }

    /** 出闸缺失暂记。 */
    @PostMapping("/estimate")
    public EstimateResponse estimate(@Valid @RequestBody EstimateRequest request) {
        return metroService.estimate(request);
    }

    /** 日结封账。 */
    @PostMapping("/clearing/seal")
    public SealResponse seal(@Valid @RequestBody SealRequest request) {
        return metroService.seal(request);
    }

    /** 查询批次明细与差异（逐笔复算）。 */
    @GetMapping("/clearing/batches/{bizDate}")
    public BatchViewResponse batchView(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return metroService.batchView(bizDate);
    }
}
