package com.example.starter.web;

import com.example.starter.service.GrainSiloService;
import com.example.starter.web.dto.AlarmRequest;
import com.example.starter.web.dto.AlarmResponse;
import com.example.starter.web.dto.ArchiveRequest;
import com.example.starter.web.dto.CreateFanRequest;
import com.example.starter.web.dto.CreateSiloRequest;
import com.example.starter.web.dto.OperationView;
import com.example.starter.web.dto.ReadingBatchRequest;
import com.example.starter.web.dto.RetestRequest;
import com.example.starter.web.dto.RetestResponse;
import com.example.starter.web.dto.RetestView;
import com.example.starter.web.dto.SiloView;
import com.example.starter.web.dto.TemperaturePoint;
import com.example.starter.web.dto.VentilationRequest;
import com.example.starter.web.dto.VentilationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 粮情测控 REST 接口。
 */
@RestController
@RequestMapping("/api")
public class GrainSiloController {

    private final GrainSiloService service;

    public GrainSiloController(GrainSiloService service) {
        this.service = service;
    }

    @PostMapping("/silos")
    @ResponseStatus(HttpStatus.CREATED)
    public SiloView createSilo(@Valid @RequestBody CreateSiloRequest request) {
        return service.createSilo(request);
    }

    @PostMapping("/fans")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createFan(@Valid @RequestBody CreateFanRequest request) {
        return service.createFan(request);
    }

    @GetMapping("/silos")
    public List<SiloView> listSilos() {
        return service.listSilos();
    }

    @GetMapping("/silos/{code}")
    public SiloView.SiloDetail getSilo(@PathVariable String code) {
        return service.getSilo(code);
    }

    @PostMapping("/alarms")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmResponse reportAlarm(@Valid @RequestBody AlarmRequest request) {
        return service.reportAlarm(request);
    }

    @PostMapping("/ventilations")
    @ResponseStatus(HttpStatus.CREATED)
    public VentilationResponse orderVentilation(@Valid @RequestBody VentilationRequest request) {
        return service.orderVentilation(request);
    }

    @PostMapping("/retests")
    @ResponseStatus(HttpStatus.CREATED)
    public RetestResponse retest(@Valid @RequestBody RetestRequest request) {
        return service.retest(request);
    }

    @PostMapping("/archives")
    @ResponseStatus(HttpStatus.CREATED)
    public Object archive(@Valid @RequestBody ArchiveRequest request) {
        return service.archive(request);
    }

    @PostMapping("/silos/{code}/readings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object uploadReadings(@PathVariable String code, @Valid @RequestBody ReadingBatchRequest request) {
        return service.uploadReadings(code, request);
    }

    @GetMapping("/silos/{code}/temperature-curve")
    public List<TemperaturePoint> temperatureCurve(@PathVariable String code) {
        return service.temperatureCurve(code);
    }

    @GetMapping("/silos/{code}/windows")
    public List<OperationView> windows(@PathVariable String code) {
        return service.windows(code);
    }

    @GetMapping("/silos/{code}/retests")
    public List<RetestView> retests(@PathVariable String code) {
        return service.retests(code);
    }
}
