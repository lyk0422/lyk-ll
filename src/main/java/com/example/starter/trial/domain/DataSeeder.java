package com.example.starter.trial.domain;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * 合成基础数据初始化：两个试验中心、其区组/随机号初始状态与两名揭盲授权人。
 * 全部为合成数据，仅在缺失时幂等写入。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    public static final String CENTER_SHANGHAI = "C-SH";
    public static final String CENTER_BEIJING = "C-BJ";
    public static final String UNBLINDER_DR_LI = "DR-LI";
    public static final String UNBLINDER_DR_WANG = "DR-WANG";

    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public DataSeeder(JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAll();
    }

    /**
     * 幂等写入全部合成基础数据；测试清空业务表后可重新调用。
     */
    public void seedAll() {
        Timestamp now = Timestamp.from(timeProvider.now());
        seedCenter(CENTER_SHANGHAI, "上海合成中心", 20260101L, TrialConstants.GROUP_TREATMENT, now);
        seedCenter(CENTER_BEIJING, "北京合成中心", 20260202L, TrialConstants.GROUP_CONTROL, now);
        seedConfirmer(UNBLINDER_DR_LI, "李医生", now);
        seedConfirmer(UNBLINDER_DR_WANG, "王医生", now);
    }

    private void seedCenter(String centerCode, String centerName, long seed, String maskAGroup, Timestamp now) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM trial_site WHERE trial_code = ? AND center_code = ?",
                Integer.class, TrialConstants.DEFAULT_TRIAL, centerCode);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO trial_site(trial_code, center_code, center_name, random_seed, mask_a_group, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                TrialConstants.DEFAULT_TRIAL, centerCode, centerName, seed, maskAGroup, now);
        jdbcTemplate.update(
                "INSERT INTO center_allocation_state(trial_code, center_code, current_block_no, allocated_in_block, updated_at_utc) "
                        + "VALUES (?, ?, 1, 0, ?)",
                TrialConstants.DEFAULT_TRIAL, centerCode, now);
        jdbcTemplate.update(
                "INSERT INTO random_number_ledger(trial_code, center_code, next_random_no) VALUES (?, ?, 1)",
                TrialConstants.DEFAULT_TRIAL, centerCode);
    }

    private void seedConfirmer(String userCode, String userName, Timestamp now) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM authorized_unblinder WHERE user_code = ?",
                Integer.class, userCode);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO authorized_unblinder(user_code, user_name, created_at_utc) VALUES (?, ?, ?)",
                userCode, userName, now);
    }
}
