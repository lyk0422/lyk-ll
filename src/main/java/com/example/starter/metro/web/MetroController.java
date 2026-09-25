package com.example.starter.metro.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.metro.repo.ClearingRepository;
import com.example.starter.metro.repo.ClearingRepository.Batch;
import com.example.starter.metro.repo.ClearingRepository.Detail;
import com.example.starter.metro.repo.ClearingRepository.Diff;
import com.example.starter.metro.service.IdempotencyService;
import com.example.starter.metro.service.SettlementService;
import com.example.starter.metro.service.TripService;
import com.example.starter.metro.web.dto.EntryRequest;
import com.example.starter.metro.web.dto.ExitRequest;
import com.example.starter.metro.web.dto.SealRequest;

import jakarta.validation.Valid;

/**
 * 轨交清分 API：进闸、出闸（含换乘计费与跨线清分）、日结封账与对账查询。
 * 写接口以 requestId 幂等：同键同参返回首次结果，同键异参返回 409。
 */
@RestController
@RequestMapping("/api/metro")
@Validated
public class MetroController {

    private final TripService tripService;
    private final SettlementService settlementService;
    private final IdempotencyService idempotencyService;
    private final ClearingRepository clearingRepository;

    public MetroController(TripService tripService, SettlementService settlementService,
            IdempotencyService idempotencyService, ClearingRepository clearingRepository) {
        this.tripService = tripService;
        this.settlementService = settlementService;
        this.idempotencyService = idempotencyService;
        this.clearingRepository = clearingRepository;
    }

    @PostMapping("/entry")
    public ResponseEntity<String> entry(@Valid @RequestBody EntryRequest request) {
        String fingerprint = idempotencyService.fingerprint(request.requestId(),
                "ENTRY|" + request.txNo() + "|" + request.ticketId() + "|" + request.station()
                        + "|" + request.occurredAt());
        String body = idempotencyService.execute(request.requestId(), fingerprint,
                () -> tripService.entry(request));
        return json(body);
    }

    @PostMapping("/exit")
    public ResponseEntity<String> exit(@Valid @RequestBody ExitRequest request) {
        String fingerprint = idempotencyService.fingerprint(request.requestId(),
                "EXIT|" + request.txNo() + "|" + request.ticketId() + "|" + request.station()
                        + "|" + request.occurredAt());
        String body = idempotencyService.execute(request.requestId(), fingerprint,
                () -> tripService.exit(request));
        return json(body);
    }

    @PostMapping("/settlement/seal")
    public ResponseEntity<String> seal(@Valid @RequestBody SealRequest request) {
        String fingerprint = idempotencyService.fingerprint(request.requestId(),
                "SEAL|" + request.batchDate());
        String body = idempotencyService.execute(request.requestId(), fingerprint,
                () -> settlementService.seal(request));
        return json(body);
    }

    /**
     * 查询批次清分结果：批次状态、合计与逐笔明细（含分摊比例），可逐笔复算。
     */
    @GetMapping("/settlement/{date}")
    public ResponseEntity<?> batch(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<Detail> details = clearingRepository.detailsForBatch(date);
        var batch = clearingRepository.findBatch(date);
        Map<String, Object> body = Map.of(
                "batchDate", date.toString(),
                "status", batch.map(Batch::status).orElse("OPEN"),
                "totalFen", batch.map(Batch::totalFen)
                        .orElseGet(() -> clearingRepository.sumForBatch(date)),
                "details", details);
        return ResponseEntity.ok(body);
    }

    /**
     * 查询对账差异：封账后迟到交易等逐笔登记。
     */
    @GetMapping("/diffs")
    public ResponseEntity<List<Diff>> diffs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(clearingRepository.diffsFor(date));
    }

    private ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
