package com.example.starter.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.CustodyService;
import com.example.starter.web.dto.AliquotView;
import com.example.starter.web.dto.CancelRequest;
import com.example.starter.web.dto.CustodyEventView;
import com.example.starter.web.dto.IntakeRequest;
import com.example.starter.web.dto.QuarantineRequest;
import com.example.starter.web.dto.ReservationView;
import com.example.starter.web.dto.ReserveRequest;
import com.example.starter.web.dto.ResultRequest;
import com.example.starter.web.dto.SpecimenView;
import com.example.starter.web.dto.SplitRequest;
import com.example.starter.web.dto.SplitResponse;
import com.example.starter.web.dto.StartRequest;
import com.example.starter.web.dto.TransferRequest;
import com.example.starter.web.dto.VolumeLedgerResponse;

/**
 * 实验室样本保管 REST 接口。
 */
@RestController
@RequestMapping("/api/custody")
public class CustodyController {

    private final CustodyService service;

    public CustodyController(CustodyService service) {
        this.service = service;
    }

    @PostMapping("/specimens")
    public ResponseEntity<SpecimenView> intake(@Valid @RequestBody IntakeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.intake(request));
    }

    @GetMapping("/specimens/{specimenId}")
    public SpecimenView getSpecimen(@PathVariable String specimenId) {
        return service.getSpecimen(specimenId);
    }

    @PostMapping("/splits")
    public SplitResponse split(@Valid @RequestBody SplitRequest request) {
        return service.split(request);
    }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationView> reserve(@Valid @RequestBody ReserveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reserve(request));
    }

    @PostMapping("/reservations/{requestId}/start")
    public ReservationView start(@PathVariable String requestId, @Valid @RequestBody StartRequest request) {
        return service.start(requestId, request);
    }

    @PostMapping("/reservations/{requestId}/cancel")
    public ReservationView cancel(@PathVariable String requestId, @Valid @RequestBody CancelRequest request) {
        return service.cancel(requestId, request);
    }

    @PostMapping("/reservations/{requestId}/results")
    public ReservationView result(@PathVariable String requestId, @Valid @RequestBody ResultRequest request) {
        return service.recordResult(requestId, request);
    }

    @GetMapping("/reservations/{requestId}")
    public ReservationView getReservation(@PathVariable String requestId) {
        return service.getReservation(requestId);
    }

    @PostMapping("/quarantines")
    public AliquotView quarantine(@Valid @RequestBody QuarantineRequest request) {
        return service.quarantine(request);
    }

    @PostMapping("/transfers")
    public CustodyEventView transfer(@Valid @RequestBody TransferRequest request) {
        return service.transfer(request);
    }

    @GetMapping("/aliquots/{aliquotId}")
    public AliquotView getAliquot(@PathVariable String aliquotId) {
        return service.getAliquot(aliquotId);
    }

    @GetMapping("/specimens/{specimenId}/ledger")
    public VolumeLedgerResponse ledger(@PathVariable String specimenId) {
        return service.ledger(specimenId);
    }

    @GetMapping("/specimens/{specimenId}/events")
    public List<CustodyEventView> specimenEvents(@PathVariable String specimenId) {
        return service.getEvents("SPECIMEN", specimenId);
    }

    @GetMapping("/aliquots/{aliquotId}/events")
    public List<CustodyEventView> aliquotEvents(@PathVariable String aliquotId) {
        return service.getEvents("ALIQUOT", aliquotId);
    }

    @GetMapping("/reservations/{requestId}/events")
    public List<CustodyEventView> reservationEvents(@PathVariable String requestId) {
        return service.getEvents("RESERVATION", requestId);
    }
}
