package com.example.starter.ride;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.IssueCertificateRequest;
import com.example.starter.ride.dto.RideDtos.OverhaulRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SupervisionInspectionRequest;

/**
 * 年度检验与大修后监督检验门禁 API：证书签发/换证、撤销、大修登记、
 * 监督检验确认与证书/撤销/资格证据查询。
 */
@RestController
@RequestMapping("/api/rides")
public class InspectionController {

    private final InspectionService service;

    public InspectionController(InspectionService service) {
        this.service = service;
    }

    @PostMapping("/{rideId}/certificates")
    public ResponseEntity<String> issueCertificate(@PathVariable long rideId,
            @Valid @RequestBody IssueCertificateRequest request) {
        return render(service.issueCertificate(rideId, request));
    }

    @PostMapping("/{rideId}/certificates/{certificateId}/revoke")
    public ResponseEntity<String> revokeCertificate(@PathVariable long rideId,
            @PathVariable long certificateId,
            @Valid @RequestBody RevokeCertificateRequest request) {
        return render(service.revokeCertificate(rideId, certificateId, request));
    }

    @GetMapping("/{rideId}/certificates")
    public List<Map<String, Object>> certificates(@PathVariable long rideId) {
        return service.listCertificates(rideId);
    }

    @PostMapping("/{rideId}/overhauls")
    public ResponseEntity<String> registerOverhaul(@PathVariable long rideId,
            @Valid @RequestBody OverhaulRequest request) {
        return render(service.registerOverhaul(rideId, request));
    }

    @GetMapping("/{rideId}/overhauls")
    public List<Map<String, Object>> overhauls(@PathVariable long rideId) {
        return service.listOverhauls(rideId);
    }

    @PostMapping("/{rideId}/supervision-inspections")
    public ResponseEntity<String> confirmSupervision(@PathVariable long rideId,
            @Valid @RequestBody SupervisionInspectionRequest request) {
        return render(service.confirmSupervision(rideId, request));
    }

    @GetMapping("/{rideId}/supervision-inspections")
    public List<Map<String, Object>> supervisionInspections(@PathVariable long rideId) {
        return service.listInspections(rideId);
    }

    @GetMapping("/{rideId}/eligibility")
    public Map<String, Object> eligibility(@PathVariable long rideId) {
        return service.eligibility(rideId);
    }

    private ResponseEntity<String> render(ApiResult result) {
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
