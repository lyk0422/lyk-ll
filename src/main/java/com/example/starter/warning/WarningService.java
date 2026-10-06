package com.example.starter.warning;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.common.IdempotencyService;
import com.example.starter.tree.TreeRepository;
import com.example.starter.warning.WarningDtos.LiftResponse;
import com.example.starter.warning.WarningDtos.LiftWarningRequest;
import com.example.starter.warning.WarningDtos.PublishWarningRequest;
import com.example.starter.warning.WarningDtos.WarningResponse;
import com.example.starter.warning.WarningRepository.WarningRow;
import org.springframework.stereotype.Service;

/**
 * 大风预警服务：发布预警与冻结高风险路段在同一事务内生效；
 * 解除预警与解除冻结同样同事务。高风险路段定义为存在一级风险树木的路段。
 */
@Service
public class WarningService {

    private final WarningRepository warningRepository;
    private final TreeRepository treeRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public WarningService(WarningRepository warningRepository, TreeRepository treeRepository,
            IdempotencyService idempotencyService, Clock clock) {
        this.warningRepository = warningRepository;
        this.treeRepository = treeRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 幂等发布：预警行与全部路段冻结记录在同一事务提交，任一失败整体回滚。
     */
    public WarningResponse publish(PublishWarningRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), request.warningNo(), request.level());
        return idempotencyService.execute(request.requestId(), fingerprint, WarningResponse.class, () -> {
            if (warningRepository.existsWarningByNo(request.warningNo())) {
                throw ApiException.conflict("预警编号已存在：" + request.warningNo());
            }
            LocalDateTime now = LocalDateTime.now(clock);
            long warningId = warningRepository.insertWarning(request.warningNo(), request.level(), now);
            List<Long> highRiskSegments = treeRepository.segmentIdsWithLevelOneTrees();
            for (Long segmentId : highRiskSegments) {
                if (!warningRepository.isFrozen(segmentId)) {
                    warningRepository.insertFreeze(segmentId, warningId, now);
                }
            }
            return new WarningResponse(warningId, request.warningNo(), request.level(), "ACTIVE",
                    now, List.copyOf(highRiskSegments));
        });
    }

    /**
     * 幂等解除：预警置为已解除并释放其冻结的路段，同一事务提交。
     */
    public LiftResponse lift(String warningNo, LiftWarningRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), warningNo);
        return idempotencyService.execute(request.requestId(), fingerprint, LiftResponse.class, () -> {
            WarningRow warning = warningRepository.findWarningByNoForUpdate(warningNo)
                    .orElseThrow(() -> ApiException.notFound("预警不存在：" + warningNo));
            if (!"ACTIVE".equals(warning.status())) {
                throw ApiException.conflict("预警已解除，不允许重复操作");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            warningRepository.markLifted(warning.id(), now);
            int released = warningRepository.releaseFreezes(warning.id());
            return new LiftResponse(warning.id(), warning.warningNo(), "LIFTED", now, released);
        });
    }
}
