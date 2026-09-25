package com.example.starter.wb.service;

import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CloseRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LateCheckinRequest;
import com.example.starter.wb.dto.LmcRequest;
import com.example.starter.wb.dto.ManifestResponse;
import com.example.starter.wb.dto.ReviewRequest;
import com.example.starter.wb.dto.StageRequest;

/**
 * 载重平衡舱单业务边界。
 */
public interface WeightBalanceService {

    ManifestResponse createManifest(CreateManifestRequest request);

    ManifestResponse checkin(long flightId, CheckinRequest request);

    ManifestResponse confirmLoad(long flightId, StageRequest request);

    ManifestResponse applyLmc(long flightId, LmcRequest request);

    ManifestResponse review(long flightId, ReviewRequest request);

    ManifestResponse close(long flightId, CloseRequest request);

    ManifestResponse getActive(long flightId);

    /** 航班全部舱单版本（含已作废），按版本号升序，用于 LMC/重打版本链复算核对。 */
    java.util.List<ManifestResponse> listVersions(long flightId);

    ManifestResponse recordLateCheckin(long flightId, LateCheckinRequest request);
}
