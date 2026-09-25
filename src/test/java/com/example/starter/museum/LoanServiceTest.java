package com.example.starter.museum;

import com.example.starter.museum.api.ContractResponse;
import com.example.starter.museum.api.HandoverResponse;
import com.example.starter.museum.domain.ContractStatus;
import com.example.starter.museum.domain.HandoverType;
import com.example.starter.museum.domain.Party;
import com.example.starter.museum.service.LoanService;
import com.example.starter.museum.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 借展主流程与边界：唯一有效合同、3 年恰满、续展第 60 日边界、双方点交、结案终态。
 */
class LoanServiceTest extends MuseumTestBase {

    @Autowired
    private LoanService loanService;

    @Test
    void exactlyThreeYearsIsLegalButOneDayMoreRejected() {
        LocalDate start = LocalDate.of(2026, 1, 1);

        ContractResponse ok = loanService.apply("A001", "省博物院", start, start.plusYears(3), "S001");
        assertThat(ok.status()).isEqualTo(ContractStatus.APPLIED);
        assertThat(ok.endDate()).isEqualTo(LocalDate.of(2029, 1, 1));

        assertThatThrownBy(() -> loanService.apply("A003", "市展览馆", start, start.plusYears(3).plusDays(1), "S003"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void gradeOneArtifactAllowsOnlyOneActiveContract() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        loanService.apply("A001", "省博物院", start, start.plusYears(1), "S001");

        assertThatThrownBy(() -> loanService.apply("A001", "市展览馆", start, start.plusMonths(6), "S002"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM artifact_active_loan a JOIN artifact ar ON ar.id = a.artifact_id WHERE ar.artifact_no = 'A001'",
                Integer.class);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void outboundHandoverRequiresBothPartiesSinglePartyHasNoEffect() {
        ContractResponse contract = applyInExhibitionReady(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));

        HandoverResponse lender = loanService.confirmHandover(
                contract.contractNo(), HandoverType.OUTBOUND, Party.LENDER);
        assertThat(lender.lenderConfirmed()).isTrue();
        assertThat(lender.borrowerConfirmed()).isFalse();
        assertThat(lender.completed()).isFalse();

        // 单方确认不生效：合同仍停留在 APPLIED
        assertThat(reload(contract.contractNo()).status()).isEqualTo(ContractStatus.APPLIED);

        HandoverResponse borrower = loanService.confirmHandover(
                contract.contractNo(), HandoverType.OUTBOUND, Party.BORROWER);
        assertThat(borrower.completed()).isTrue();
        ContractResponse inExhibition = reload(contract.contractNo());
        assertThat(inExhibition.status()).isEqualTo(ContractStatus.IN_EXHIBITION);
    }

    @Test
    void renewalAcceptedExactly60DaysBeforeEndButRejectedOnDay61() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 7, 1); // 181 天
        ContractResponse contract = applyInExhibition(start, end);

        // 距届满 61 天：拒绝
        fixClock("2026-05-01T00:00:00Z");
        assertThatThrownBy(() -> loanService.renew(contract.contractNo(), LocalDate.of(2026, 8, 1), true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("60");

        // 恰第 60 日：受理（2026-05-02 至 2026-07-01 恰 60 个自然日）
        fixClock("2026-05-02T00:00:00Z");
        ContractResponse renewed = loanService.renew(contract.contractNo(), LocalDate.of(2026, 8, 1), true);
        assertThat(renewed.endDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(renewed.renewCount()).isEqualTo(1);

        // 仅允许续展一次
        assertThatThrownBy(() -> loanService.renew(contract.contractNo(), LocalDate.of(2026, 9, 1), true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("续展一次");
    }

    @Test
    void renewalMustNotExceedHalfOfOriginalTermOrThreeYearsTotal() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = start.plusDays(100);
        ContractResponse contract = applyInExhibition(start, end);

        fixClock(end.minusDays(60).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString());
        // 增量 60 天 > 原期限 100 天的一半（50 天）
        assertThatThrownBy(() -> loanService.renew(contract.contractNo(), end.plusDays(60), true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("一半");

        // 恰 50 天：合法
        ContractResponse renewed = loanService.renew(contract.contractNo(), end.plusDays(50), true);
        assertThat(renewed.endDate()).isEqualTo(end.plusDays(50));
    }

    @Test
    void rejectedRenewalKeepsEndDateAndDoesNotConsumeRenewalChance() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 4, 1);
        ContractResponse contract = applyInExhibition(start, end);
        fixClock("2026-02-01T00:00:00Z"); // 距届满 59 天

        loanService.renew(contract.contractNo(), end.plusDays(10), false);
        ContractResponse unchanged = reload(contract.contractNo());
        assertThat(unchanged.endDate()).isEqualTo(end);
        assertThat(unchanged.renewCount()).isZero();
    }

    @Test
    void returnHandoverThenCloseIsTerminalAndRepeatedCloseIsIdempotent() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        ContractResponse contract = applyInExhibition(start, start.plusYears(1));

        // 未归还不能结案
        assertThatThrownBy(() -> loanService.close(contract.contractNo()))
                .isInstanceOf(ApiException.class);

        loanService.confirmHandover(contract.contractNo(), HandoverType.RETURN, Party.LENDER);
        assertThat(reload(contract.contractNo()).status()).isEqualTo(ContractStatus.IN_EXHIBITION);
        loanService.confirmHandover(contract.contractNo(), HandoverType.RETURN, Party.BORROWER);
        assertThat(reload(contract.contractNo()).status()).isEqualTo(ContractStatus.RETURNED);

        // 已归还但未结案：仍占用有效合同名额，同一一级文物不能再申请
        assertThatThrownBy(() -> loanService.apply("A002", "抢先展方",
                        start.plusYears(2), start.plusYears(3), "S002"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        ContractResponse closed = loanService.close(contract.contractNo());
        assertThat(closed.status()).isEqualTo(ContractStatus.CLOSED);

        // 重复结案：幂等成功
        ContractResponse again = loanService.close(contract.contractNo());
        assertThat(again.status()).isEqualTo(ContractStatus.CLOSED);
        assertThat(again.closedAt()).isEqualTo(closed.closedAt());

        // 结案后占用名额释放，同一一级文物可再次借展
        ContractResponse second = loanService.apply("A002", "另一博物院",
                start.plusYears(2), start.plusYears(3), "S002");
        assertThat(second.status()).isEqualTo(ContractStatus.APPLIED);
    }

    private ContractResponse applyInExhibitionReady(LocalDate start, LocalDate end) {
        return loanService.apply("A002", "织绣馆", start, end, "S002");
    }

    private ContractResponse applyInExhibition(LocalDate start, LocalDate end) {
        ContractResponse c = applyInExhibitionReady(start, end);
        loanService.confirmHandover(c.contractNo(), HandoverType.OUTBOUND, Party.LENDER);
        loanService.confirmHandover(c.contractNo(), HandoverType.OUTBOUND, Party.BORROWER);
        return reload(c.contractNo());
    }

    private ContractResponse reload(String contractNo) {
        return loanService.getContract(contractNo);
    }
}
