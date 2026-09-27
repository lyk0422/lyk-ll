package com.example.starter.survey.service;

import com.example.starter.survey.data.ExternalReportRepository;
import com.example.starter.survey.data.RecognitionBindingRepository;
import com.example.starter.survey.data.RecognitionRuleRepository;
import com.example.starter.survey.domain.ExternalReport;
import com.example.starter.survey.domain.ExternalReportStatus;
import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.RecognitionRuleVersion;
import com.example.starter.survey.support.RecognitionConstants;
import com.example.starter.survey.web.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 发证门禁中的互认证据冻结校验。
 * 互认只替代映射项目的完成证明，不替代年度窗口、重大缺陷整改等原签发门禁（仍由发证主流程负责）；
 * 本组件只保证发证一刻引用的报告修订与规则版本一致有效：
 * <ol>
 *   <li>先按主键升序行锁引用的报告修订行（与“报告更正”争抢同一行，锁顺序全局一致以防死锁）；</li>
 *   <li>再行锁该检验全部有效绑定；</li>
 *   <li>修订必须仍为 CURRENT、证据未超过最长有效天数，否则当场失效绑定并拒绝发证。</li>
 * </ol>
 * 必须运行在发证事务内（与证书插入同事务）。
 */
@Component
public class RecognitionCertifyGate {

    private final RecognitionBindingRepository bindingRepository;
    private final ExternalReportRepository reportRepository;
    private final RecognitionRuleRepository ruleRepository;

    public RecognitionCertifyGate(RecognitionBindingRepository bindingRepository,
                                  ExternalReportRepository reportRepository,
                                  RecognitionRuleRepository ruleRepository) {
        this.bindingRepository = bindingRepository;
        this.reportRepository = reportRepository;
        this.ruleRepository = ruleRepository;
    }

    /**
     * 发证事务内冻结并校验该检验的全部有效互认绑定。
     * 任一绑定引用的修订已被更正替代或证据已过最长有效天数，则失效该绑定并抛 422，不得发证。
     */
    public void verifyFrozen(long surveyId, Instant now) {
        List<RecognitionBinding> snapshot = bindingRepository.findActiveBySurvey(surveyId);
        if (snapshot.isEmpty()) {
            return;
        }
        Set<Long> snapshotIds = new HashSet<>();
        snapshot.forEach(b -> snapshotIds.add(b.id()));
        // 先锁报告修订行（升序），与报告更正流程的“先锁当前修订行”锁顺序保持一致。
        List<Long> reportIds = snapshot.stream().map(RecognitionBinding::reportId)
                .distinct().sorted(Comparator.naturalOrder()).toList();
        for (Long reportId : reportIds) {
            reportRepository.findByIdForUpdate(reportId)
                    .orElseThrow(() -> new BusinessRuleException("RECOGNITION_REPORT_MISSING",
                            "互认引用的报告修订缺失: reportId=" + reportId));
        }
        // 报告行锁定后再锁绑定行：并发更正/新绑定都需先持有报告行锁，无法在此插入交错。
        List<RecognitionBinding> locked = bindingRepository.findActiveBySurveyForUpdate(surveyId);
        Set<Long> lockedIds = new HashSet<>();
        locked.forEach(b -> lockedIds.add(b.id()));
        // 冻结一致：锁等待期间若有效绑定集合发生增减（并发更正失效旧绑定或并发新绑定），
        // 不能按变更后的状态发证，拒绝并要求客户端重试。
        if (!lockedIds.equals(snapshotIds)) {
            throw new BusinessRuleException("RECOGNITION_FREEZE_CHANGED",
                    "发证锁等待期间互认绑定集合发生变化，未冻结到一致修订/规则版本，"
                            + "请重新读取后再发证: surveyId=" + surveyId);
        }
        for (RecognitionBinding binding : locked) {
            ExternalReport report = reportRepository.findById(binding.reportId()).orElseThrow();
            if (report.status() != ExternalReportStatus.CURRENT) {
                bindingRepository.invalidate(binding.id(),
                        RecognitionConstants.REASON_REPORT_REVISED, now);
                throw new BusinessRuleException("RECOGNITION_REVISION_CHANGED",
                        "报告 " + binding.reportNo() + " 修订 " + binding.revisionNo()
                                + " 已被更正替代，不得以旧互认可用标志发证；请引用新修订重新绑定");
            }
            RecognitionRuleVersion rule = ruleRepository.findByVersion(binding.ruleVersion())
                    .orElseThrow(() -> new BusinessRuleException("RECOGNITION_RULE_MISSING",
                            "互认绑定冻结的规则版本缺失: version=" + binding.ruleVersion()));
            Instant deadline = report.completedAt()
                    .plus(Duration.ofDays(rule.maxEvidenceValidDays()));
            if (now.isAfter(deadline)) {
                bindingRepository.invalidate(binding.id(), "EVIDENCE_EXPIRED", now);
                throw new BusinessRuleException("EVIDENCE_EXPIRED",
                        "互认证据已超过最长有效天数（截止 " + deadline + "，UTC，恰截止允许），"
                                + "不得发证: reportNo=" + binding.reportNo());
            }
        }
    }
}
