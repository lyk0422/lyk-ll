package com.example.starter.survey.service;

import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.CreateShipRequest;
import com.example.starter.survey.web.dto.ShipResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 船舶登记与查询（合成数据）。 */
@Service
public class ShipService {

    private final ShipRepository shipRepository;
    private final IdempotentExecutor executor;

    public ShipService(ShipRepository shipRepository, IdempotentExecutor executor) {
        this.shipRepository = shipRepository;
        this.executor = executor;
    }

    /** 幂等登记船舶；IMO 唯一。 */
    public ApiResult<ShipResponse> register(CreateShipRequest request) {
        String fp = Fingerprint.of(request.requestId(),
                "CREATE_SHIP",
                Map.of("imo", request.imo(),
                        "name", request.name(),
                        "annualAnniversary", request.annualAnniversary().toString(),
                        "deliveryDate", request.deliveryDate().toString()));
        return executor.run(request.requestId(), fp, ShipResponse.class, () -> {
            try {
                Ship ship = shipRepository.insert(request.imo(), request.name(),
                        request.annualAnniversary(), request.deliveryDate(), executor.now());
                return ShipResponse.from(ship);
            } catch (DuplicateKeyException e) {
                throw new ConflictException("SHIP_IMO_EXISTS", "船舶 IMO 已存在: " + request.imo());
            }
        });
    }

    /** 按 IMO 查询。 */
    public ShipResponse getByImo(String imo) {
        Ship ship = shipRepository.findByImo(imo)
                .orElseThrow(() -> new NotFoundException("SHIP_NOT_FOUND", "船舶不存在: " + imo));
        return ShipResponse.from(ship);
    }

    /** 列出全部合成船舶。 */
    public List<ShipResponse> list() {
        return shipRepository.findAll().stream().map(ShipResponse::from).toList();
    }
}
