package com.example.starter.service;

import com.example.starter.domain.BreachEvent;
import com.example.starter.domain.BreachStatus;
import com.example.starter.domain.BrakeCommand;
import com.example.starter.domain.OverlapZone;
import com.example.starter.repo.AuthorizationRepository;
import com.example.starter.repo.BrakeRepository;
import com.example.starter.repo.BreachRepository;
import com.example.starter.repo.CraneRepository;
import com.example.starter.repo.PoseRepository;
import com.example.starter.repo.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 位姿 ingest 与越限处置服务。规则：
 * <ul>
 *   <li>读数按（塔机，时间戳）去重，重复读数不重复触发预警；乱序到达按时间戳归位；</li>
 *   <li>吊钩进入未授权重叠区即生成越限预警；持续 3 秒未退出自动下发制动指令；</li>
 *   <li>制动幂等：同一越限事件只下发一次，重复触发返回首次指令；</li>
 *   <li>授权恰结束时刻的位姿点按授权内处理；释放时刻之前的读数不受已释放影响。</li>
 * </ul>
 */
@Service
public class PoseService {

    /** 持续越限时长达到该值即自动制动。 */
    static final Duration BRAKE_THRESHOLD = Duration.ofSeconds(3);

    private final PoseRepository poseRepo;
    private final CraneRepository craneRepo;
    private final ZoneRepository zoneRepo;
    private final AuthorizationRepository authRepo;
    private final BreachRepository breachRepo;
    private final BrakeRepository brakeRepo;
    private final Clock clock;

    public PoseService(PoseRepository poseRepo, CraneRepository craneRepo, ZoneRepository zoneRepo,
                       AuthorizationRepository authRepo, BreachRepository breachRepo,
                       BrakeRepository brakeRepo, Clock clock) {
        this.poseRepo = poseRepo;
        this.craneRepo = craneRepo;
        this.zoneRepo = zoneRepo;
        this.authRepo = authRepo;
        this.breachRepo = breachRepo;
        this.brakeRepo = brakeRepo;
        this.clock = clock;
    }

    /**
     * 位姿处理结果。duplicate=true 表示重复读数，未触发任何判定。
     */
    public record PoseIngestResult(boolean duplicate,
                                   List<BreachEvent> breachEvents,
                                   List<BrakeCommand> brakeCommands) {
    }

    @Transactional
    public PoseIngestResult ingest(String craneId, Instant tsUtc, double hookXM, double hookYM) {
        craneRepo.findById(craneId)
                .orElseThrow(() -> ApiException.notFound("塔机不存在: " + craneId));
        if (tsUtc == null) {
            throw ApiException.unprocessable("INVALID_POSE", "位姿时间戳不能为空（UTC）");
        }

        boolean inserted = poseRepo.insertIfAbsent(craneId, tsUtc, hookXM, hookYM, clock.instant());
        if (!inserted) {
            // 重复读数：去重返回，不重复触发预警
            return new PoseIngestResult(true, List.of(), List.of());
        }

        List<OverlapZone> allZones = zoneRepo.findAll();
        Map<String, OverlapZone> containing = allZones.stream()
                .filter(z -> z.contains(hookXM, hookYM))
                .collect(Collectors.toMap(OverlapZone::id, Function.identity()));
        List<BreachEvent> openBreaches = breachRepo.findOpenByCrane(craneId);

        // 对涉及的重叠区按编号排序加锁，与授权申请/释放串行化且避免死锁
        Map<String, OverlapZone> involved = new TreeMap<>(containing);
        openBreaches.forEach(b -> allZones.stream()
                .filter(z -> z.id().equals(b.zoneId())).findFirst()
                .ifPresent(z -> involved.putIfAbsent(z.id(), z)));
        involved.keySet().forEach(zoneRepo::lockById);

        List<BreachEvent> touched = new ArrayList<>();
        List<BrakeCommand> commands = new ArrayList<>();

        for (OverlapZone zone : containing.values()) {
            boolean authorized = authRepo.findCovering(craneId, zone.id(), tsUtc).stream()
                    .anyMatch(a -> a.coversPoseAt(tsUtc));
            if (authorized) {
                continue;
            }
            BreachEvent breach = breachRepo.findOpenByCraneAndZone(craneId, zone.id()).orElse(null);
            if (breach == null) {
                breach = breachRepo.insert(craneId, zone.id(), tsUtc, clock.instant());
            } else {
                // 乱序归位：首越限取更早、末越限取更晚
                Instant first = tsUtc.isBefore(breach.firstDetectedAtUtc()) ? tsUtc : breach.firstDetectedAtUtc();
                Instant last = tsUtc.isAfter(breach.lastDetectedAtUtc()) ? tsUtc : breach.lastDetectedAtUtc();
                if (!first.equals(breach.firstDetectedAtUtc()) || !last.equals(breach.lastDetectedAtUtc())) {
                    breachRepo.updateWindow(breach.id(), first, last);
                    breach = new BreachEvent(breach.id(), breach.craneId(), breach.zoneId(), first, last,
                            breach.status(), breach.resolvedAtUtc(), breach.createdAtUtc());
                }
            }
            Duration sustained = Duration.between(breach.firstDetectedAtUtc(), breach.lastDetectedAtUtc());
            if (breach.status() == BreachStatus.OPEN && sustained.compareTo(BRAKE_THRESHOLD) >= 0) {
                // 持续 3 秒未退出：自动下发制动（幂等，同一事件只下发一次）
                BrakeCommand command = brakeRepo.issueOnce(breach.id(), craneId, zone.id(), tsUtc,
                        "持续越限 " + sustained.toSeconds() + " 秒未退出重叠区 " + zone.id());
                breachRepo.markBraked(breach.id());
                breach = new BreachEvent(breach.id(), breach.craneId(), breach.zoneId(),
                        breach.firstDetectedAtUtc(), breach.lastDetectedAtUtc(), BreachStatus.BRAKED,
                        breach.resolvedAtUtc(), breach.createdAtUtc());
                commands.add(command);
            }
            touched.add(breach);
        }

        // 退出判定：读数时刻晚于未了结事件的末次越限时刻，且吊钩已不在该区域
        for (BreachEvent breach : openBreaches) {
            if (!containing.containsKey(breach.zoneId()) && tsUtc.isAfter(breach.lastDetectedAtUtc())) {
                breachRepo.markResolved(breach.id(), tsUtc);
            }
        }

        return new PoseIngestResult(false, touched, commands);
    }
}
