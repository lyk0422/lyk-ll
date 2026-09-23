package com.example.starter.rights.service;

import com.example.starter.rights.ApiException;
import com.example.starter.rights.AppClock;
import com.example.starter.rights.LicenseMode;
import com.example.starter.rights.LicenseStatus;
import com.example.starter.rights.ScheduleItemStatus;
import com.example.starter.rights.dto.CompletePlaybackRequest;
import com.example.starter.rights.dto.CorrectLicenseRequest;
import com.example.starter.rights.dto.CreateLicenseRequest;
import com.example.starter.rights.dto.EvidenceView;
import com.example.starter.rights.dto.LicenseView;
import com.example.starter.rights.dto.PlayabilityView;
import com.example.starter.rights.dto.PlaybackReceipt;
import com.example.starter.rights.dto.RevokeLicenseRequest;
import com.example.starter.rights.dto.ScheduleItemView;
import com.example.starter.rights.dto.ScheduleSegment;
import com.example.starter.rights.dto.SubmitScheduleRequest;
import com.example.starter.rights.dto.SubmissionView;
import com.example.starter.rights.model.LicenseVersion;
import com.example.starter.rights.model.PlaybackRecord;
import com.example.starter.rights.model.ScheduleItem;
import com.example.starter.rights.repo.RightsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 媒体版权窗口与播出授权核心业务：
 * 半开窗口 [start,end)、独占冲突、版本化更正、撤销联动、整笔排期与冻结证据。
 */
@Service
public class RightsService {

    private final RightsRepository repo;
    private final IdempotentExecutor idempotency;
    private final AppClock clock;

    public RightsService(RightsRepository repo, IdempotentExecutor idempotency, AppClock clock) {
        this.repo = repo;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---- 授权创建 / 更正 / 撤销 ----

    @Transactional
    public LicenseView createLicense(CreateLicenseRequest req) {
        validateWindow(req.start(), req.end());
        String fp = idempotency.fingerprint("CREATE_LICENSE", List.of(
                req.assetId(), req.region(), req.platform(),
                req.start().toString(), req.end().toString(), req.mode().name()));
        return idempotency.execute(req.requestId(), "CREATE_LICENSE", fp, LicenseView.class, () -> {
            Instant now = clock.now();
            String licenseId = "lic_" + UUID.randomUUID();
            repo.lockScope(req.assetId(), req.region(), req.platform());
            assertNoExclusiveConflict(req.assetId(), req.region(), req.platform(),
                    req.start(), req.end(), licenseId, req.mode());
            LicenseVersion v = new LicenseVersion(0, licenseId, 1,
                    req.assetId(), req.region(), req.platform(),
                    req.start(), req.end(), req.mode(), LicenseStatus.ACTIVE,
                    now, null, req.requestId());
            repo.insertLicenseVersion(v);
            return toView(repo.findActiveVersion(licenseId).orElseThrow());
        });
    }

    @Transactional
    public LicenseView correctLicense(CorrectLicenseRequest req) {
        validateWindow(req.start(), req.end());
        String fp = idempotency.fingerprint("CORRECT_LICENSE", List.of(
                req.licenseId(), String.valueOf(req.expectedVersion()),
                req.start().toString(), req.end().toString(), req.mode().name()));
        return idempotency.execute(req.requestId(), "CORRECT_LICENSE", fp, LicenseView.class, () -> {
            Instant now = clock.now();
            LicenseVersion current = repo.findActiveVersion(req.licenseId())
                    .orElseThrow(() -> licenseNotActive(req.licenseId()));
            if (req.expectedVersion() != null && req.expectedVersion() != current.versionNo()) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "预期版本 " + req.expectedVersion() + " 与当前版本 " + current.versionNo() + " 不一致");
            }
            repo.lockScope(current.assetId(), current.region(), current.platform());
            assertNoExclusiveConflict(current.assetId(), current.region(), current.platform(),
                    req.start(), req.end(), current.licenseId(), req.mode());
            // 旧版本保留为 SUPERSEDED，不覆盖历史；条件更新失败说明并发更正/撤销抢先。
            int updated = repo.supersedeActive(current.licenseId(), current.versionNo());
            if (updated == 0) {
                throw ApiException.conflict("CONCURRENT_MODIFICATION", "授权已被并发修改，请重试");
            }
            LicenseVersion next = new LicenseVersion(0, current.licenseId(), current.versionNo() + 1,
                    current.assetId(), current.region(), current.platform(),
                    req.start(), req.end(), req.mode(), LicenseStatus.ACTIVE,
                    now, null, req.requestId());
            repo.insertLicenseVersion(next);
            return toView(repo.findActiveVersion(current.licenseId()).orElseThrow());
        });
    }

    @Transactional
    public LicenseView revokeLicense(RevokeLicenseRequest req) {
        String fp = idempotency.fingerprint("REVOKE_LICENSE",
                List.of(req.licenseId(), String.valueOf(req.expectedVersion())));
        return idempotency.execute(req.requestId(), "REVOKE_LICENSE", fp, LicenseView.class, () -> {
            Instant now = clock.now();
            LicenseVersion current = repo.findActiveVersion(req.licenseId())
                    .orElseThrow(() -> licenseNotActive(req.licenseId()));
            if (req.expectedVersion() != null && req.expectedVersion() != current.versionNo()) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "预期版本 " + req.expectedVersion() + " 与当前版本 " + current.versionNo() + " 不一致");
            }
            int updated = repo.markRevoked(current.licenseId(), current.versionNo(), now);
            if (updated == 0) {
                throw ApiException.conflict("CONCURRENT_MODIFICATION", "授权已被并发修改，请重试");
            }
            // 仅阻断撤销时刻尚未开始的已绑定时段；已开始/已完成的不受追溯影响。
            repo.blockNotStartedItems(current.licenseId(), current.versionNo(), now);
            return toView(repo.findVersions(current.licenseId()).stream()
                    .filter(v -> v.versionNo() == current.versionNo())
                    .findFirst().orElseThrow());
        });
    }

    // ---- 排期提交 ----

    @Transactional
    public SubmissionView submitSchedule(SubmitScheduleRequest req) {
        req.segments().forEach(s -> validateWindow(s.start(), s.end()));
        String fp = idempotency.fingerprint("SUBMIT_SCHEDULE", req.segments().stream()
                .map(s -> List.of(s.assetId(), s.region(), s.platform(),
                        s.start().toString(), s.end().toString()))
                .toList());
        return idempotency.execute(req.requestId(), "SUBMIT_SCHEDULE", fp, SubmissionView.class, () -> {
            Instant now = clock.now();
            String submissionId = "sub_" + UUID.randomUUID();
            List<ScheduleItem> bound = new ArrayList<>();
            for (int i = 0; i < req.segments().size(); i++) {
                ScheduleSegment seg = req.segments().get(i);
                final int index = i;
                // 行锁串行化“撤销 vs 排期提交”：撤销先提交则查不到 ACTIVE 覆盖，整笔拒绝；
                // 排期先提交则撤销随后只能把未开始项置 BLOCKED。
                LicenseVersion cover = repo.findActiveCoverForUpdate(
                        seg.assetId(), seg.region(), seg.platform(), seg.start(), seg.end())
                        .orElseThrow(() -> ApiException.unprocessable("SEGMENT_NOT_COVERED",
                                "第 " + index + " 段 [" + seg.start() + "," + seg.end()
                                        + ") 无有效且整段覆盖的授权版本"));
                bound.add(new ScheduleItem(0, submissionId, i,
                        seg.assetId(), seg.region(), seg.platform(),
                        seg.start(), seg.end(), ScheduleItemStatus.BOUND,
                        cover.licenseId(), cover.versionNo(), now, null));
            }
            repo.insertSubmission(submissionId, req.requestId(), bound.size(), now);
            bound.forEach(repo::insertItem);
            return new SubmissionView(submissionId, now, bound.stream().map(this::toItemView).toList());
        });
    }

    // ---- 播出回执 ----

    @Transactional
    public PlaybackReceipt completePlayback(String submissionId, int itemIndex, CompletePlaybackRequest req) {
        String fp = idempotency.fingerprint("COMPLETE_PLAYBACK", List.of(
                submissionId, String.valueOf(itemIndex),
                String.valueOf(req.airStart()), String.valueOf(req.airEnd())));
        return idempotency.execute(req.requestId(), "COMPLETE_PLAYBACK", fp, PlaybackReceipt.class, () -> {
            Instant now = clock.now();
            ScheduleItem item = repo.findItem(submissionId, itemIndex)
                    .orElseThrow(() -> ApiException.notFound("排期时段不存在: " + submissionId + "#" + itemIndex));
            if (item.status() == ScheduleItemStatus.BLOCKED) {
                throw ApiException.conflict("ITEM_BLOCKED", "该时段已因授权撤销被阻断，不能播出");
            }
            Instant airStart = req.airStart() != null ? req.airStart() : item.segmentStart();
            Instant airEnd = req.airEnd() != null ? req.airEnd() : item.segmentEnd();
            if (!airStart.equals(item.segmentStart()) || !airEnd.equals(item.segmentEnd())) {
                throw ApiException.unprocessable("AIR_TIME_MISMATCH",
                        "播出时间必须与排期时段一致: [" + item.segmentStart() + "," + item.segmentEnd() + ")");
            }
            if (now.isBefore(airEnd)) {
                throw ApiException.unprocessable("PLAYBACK_NOT_FINISHED", "时段尚未播完，不能回执完成");
            }
            LicenseVersion lic = repo.findVersion(item.licenseId(), item.licenseVersion())
                    .orElseThrow(() -> ApiException.notFound("绑定的授权版本不存在"));
            if (repo.findPlayback(submissionId, itemIndex).isPresent()) {
                throw ApiException.conflict("PLAYBACK_EXISTS", "该时段已有完成回执");
            }
            PlaybackRecord record = new PlaybackRecord(0, submissionId, itemIndex,
                    airStart, airEnd, lic.licenseId(), lic.versionNo(),
                    lic.windowStart(), lic.windowEnd(), lic.mode(), now, req.requestId());
            repo.insertPlayback(record);
            return new PlaybackReceipt(submissionId, itemIndex, airStart, airEnd,
                    lic.licenseId(), lic.versionNo(),
                    lic.windowStart(), lic.windowEnd(), lic.mode(), now);
        });
    }

    // ---- 查询：可播校验 / 历史证据 / 版本历史 ----

    @Transactional(readOnly = true)
    public PlayabilityView checkPlayable(String submissionId, int itemIndex) {
        Instant now = clock.now();
        ScheduleItem item = repo.findItem(submissionId, itemIndex).orElse(null);
        if (item == null) {
            return PlayabilityView.blocked("SCHEDULE_NOT_FOUND");
        }
        if (item.status() == ScheduleItemStatus.BLOCKED) {
            return PlayabilityView.blocked("ITEM_BLOCKED");
        }
        LicenseVersion lic = repo.findVersion(item.licenseId(), item.licenseVersion()).orElse(null);
        if (lic == null) {
            return PlayabilityView.blocked("LICENSE_VERSION_NOT_FOUND");
        }
        boolean covers = !lic.windowStart().isAfter(item.segmentStart())
                && !item.segmentEnd().isAfter(lic.windowEnd());
        if (!covers) {
            return PlayabilityView.blocked("WINDOW_NOT_COVERED");
        }
        if (lic.status() == LicenseStatus.REVOKED && now.isBefore(item.segmentStart())) {
            return PlayabilityView.blocked("LICENSE_REVOKED_NOT_STARTED");
        }
        return PlayabilityView.ok(lic.licenseId(), lic.versionNo());
    }

    @Transactional(readOnly = true)
    public SubmissionView getSubmission(String submissionId) {
        List<ScheduleItem> items = repo.findItems(submissionId);
        if (items.isEmpty()) {
            throw ApiException.notFound("排期不存在: " + submissionId);
        }
        return new SubmissionView(submissionId, items.get(0).boundAt(),
                items.stream().map(this::toItemView).toList());
    }

    @Transactional(readOnly = true)
    public List<LicenseView> getLicenseHistory(String licenseId) {
        List<LicenseVersion> versions = repo.findVersions(licenseId);
        if (versions.isEmpty()) {
            throw ApiException.notFound("授权不存在: " + licenseId);
        }
        return versions.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<EvidenceView> getEvidenceBySubmission(String submissionId) {
        return repo.findEvidenceBySubmission(submissionId).stream().map(this::toEvidence).toList();
    }

    @Transactional(readOnly = true)
    public List<EvidenceView> getEvidenceByLicense(String licenseId) {
        return repo.findEvidenceByLicense(licenseId).stream().map(this::toEvidence).toList();
    }

    // ---- 内部规则 ----

    private void assertNoExclusiveConflict(String assetId, String region, String platform,
                                           Instant start, Instant end, String licenseId, LicenseMode mode) {
        List<LicenseVersion> overlaps = repo.findActiveOverlaps(
                assetId, region, platform, start, end, licenseId);
        for (LicenseVersion other : overlaps) {
            if (mode == LicenseMode.EXCLUSIVE || other.mode() == LicenseMode.EXCLUSIVE) {
                throw ApiException.conflict("EXCLUSIVE_CONFLICT",
                        "与授权 " + other.licenseId() + " v" + other.versionNo()
                                + " 存在正长度时间交集且涉及独占模式");
            }
        }
    }

    private void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw ApiException.unprocessable("INVALID_WINDOW", "窗口必须为 start < end 的正长度半开区间");
        }
    }

    private ApiException licenseNotActive(String licenseId) {
        return ApiException.conflict("LICENSE_NOT_ACTIVE", "授权不存在或无 ACTIVE 版本: " + licenseId);
    }

    private LicenseView toView(LicenseVersion v) {
        return new LicenseView(v.licenseId(), v.versionNo(), v.assetId(), v.region(), v.platform(),
                v.windowStart(), v.windowEnd(), v.mode(), v.status(), v.createdAt(), v.revokedAt());
    }

    private ScheduleItemView toItemView(ScheduleItem i) {
        return new ScheduleItemView(i.itemIndex(), i.assetId(), i.region(), i.platform(),
                i.segmentStart(), i.segmentEnd(), i.status(),
                i.licenseId(), i.licenseVersion(), i.boundAt(), i.blockedAt());
    }

    private EvidenceView toEvidence(PlaybackRecord r) {
        return new EvidenceView(r.submissionId(), r.itemIndex(), r.airStart(), r.airEnd(),
                r.licenseId(), r.licenseVersion(), r.windowStart(), r.windowEnd(),
                r.mode().name(), r.completedAt(), r.requestId());
    }
}
