package com.example.starter.torque.web;

import com.example.starter.torque.TorqueService;
import com.example.starter.torque.dto.CardView;
import com.example.starter.torque.dto.DispatchRequest;
import com.example.starter.torque.dto.InspectionRequest;
import com.example.starter.torque.dto.InspectionResult;
import com.example.starter.torque.dto.LateMeasurementView;
import com.example.starter.torque.dto.ReleaseRequest;
import com.example.starter.torque.dto.ReleaseResult;
import com.example.starter.torque.dto.ReworkView;
import com.example.starter.torque.dto.TighteningRecordView;
import com.example.starter.torque.dto.TighteningRequest;
import com.example.starter.torque.dto.TighteningResult;
import com.example.starter.torque.dto.ToolRegisterRequest;
import com.example.starter.torque.dto.ToolView;
import com.example.starter.torque.dto.WitnessEventView;
import com.example.starter.torque.dto.WitnessRequest;
import com.example.starter.torque.dto.WitnessResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工序流转 REST 入口：派工→紧固→自检/互检→见证→放行归档。
 */
@RestController
@RequestMapping("/api/cards")
public class TorqueController {

    private final TorqueService torqueService;

    public TorqueController(TorqueService torqueService) {
        this.torqueService = torqueService;
    }

    @PostMapping("/dispatch")
    public ResponseEntity<CardView> dispatch(@Valid @RequestBody DispatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(torqueService.dispatch(request));
    }

    @PostMapping("/tools")
    public ToolView registerTool(@Valid @RequestBody ToolRegisterRequest request) {
        return torqueService.registerTool(request);
    }

    @GetMapping("/{cardNo}")
    public CardView getCard(@PathVariable String cardNo) {
        return torqueService.getCard(cardNo);
    }

    /** 力矩曲线复算（只追加链，含迟到测量标记）。 */
    @GetMapping("/{cardNo}/chain/tightening")
    public List<TighteningRecordView> tighteningChain(@PathVariable String cardNo) {
        return torqueService.getTighteningChain(cardNo);
    }

    /** 见证链复算。 */
    @GetMapping("/{cardNo}/chain/witness")
    public List<WitnessEventView> witnessChain(@PathVariable String cardNo) {
        return torqueService.getWitnessChain(cardNo);
    }

    /** 返工台账复算。 */
    @GetMapping("/{cardNo}/chain/reworks")
    public List<ReworkView> reworks(@PathVariable String cardNo) {
        return torqueService.getReworkLedger(cardNo);
    }

    /** 归档后迟到测量事件复算。 */
    @GetMapping("/{cardNo}/chain/late-measurements")
    public List<LateMeasurementView> lateMeasurements(@PathVariable String cardNo) {
        return torqueService.getLateMeasurements(cardNo);
    }

    @PostMapping("/{cardNo}/tighten")
    public TighteningResult tighten(@PathVariable String cardNo,
                                    @Valid @RequestBody TighteningRequest request) {
        return torqueService.tighten(cardNo, request);
    }

    @PostMapping("/{cardNo}/inspections")
    public InspectionResult inspect(@PathVariable String cardNo,
                                    @Valid @RequestBody InspectionRequest request) {
        return torqueService.inspect(cardNo, request);
    }

    @PostMapping("/{cardNo}/witness")
    public WitnessResult witness(@PathVariable String cardNo,
                                 @Valid @RequestBody WitnessRequest request) {
        return torqueService.witness(cardNo, request);
    }

    @PostMapping("/{cardNo}/release")
    public ReleaseResult release(@PathVariable String cardNo,
                                 @Valid @RequestBody ReleaseRequest request) {
        return torqueService.release(cardNo, request);
    }
}
