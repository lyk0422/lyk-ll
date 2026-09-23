package com.example.starter.trajectory.service;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.example.starter.trajectory.TestFixtures.rules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class RuleConfigServiceH2Test {

    @Autowired
    private RuleConfigService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM validation_record");
        jdbcTemplate.update("DELETE FROM rule_config");
    }

    @Test
    void draftCanBeUpdatedButPublishedConfigIsImmutable() {
        RuleConfig v1 = service.createDraft(rules("v-immutable"));
        assertEquals(RuleStatus.DRAFT, v1.status());

        RuleConfig updated = service.updateDraft("v-immutable",
                new RuleConfig("v-immutable", 0.5, 8, 4, 0.05, 0.1, 1.5, RuleStatus.DRAFT));
        assertEquals(0.5, updated.maxTimeGapSeconds(), 1e-12);

        RuleConfig published = service.publish("v-immutable");
        assertEquals(RuleStatus.PUBLISHED, published.status());

        assertThrows(PublishedRuleImmutableException.class,
                () -> service.updateDraft("v-immutable",
                        new RuleConfig("v-immutable", 0.2, 8, 4, 0.05, 0.1, 1.5, RuleStatus.DRAFT)));

        RuleConfig stored = service.get("v-immutable");
        assertEquals(0.5, stored.maxTimeGapSeconds(), 1e-12);
        assertEquals(RuleStatus.PUBLISHED, stored.status());
    }

    @Test
    void duplicateVersionAndMissingVersionAreRejected() {
        service.createDraft(rules("v-dup"));
        assertThrows(DuplicateRuleVersionException.class, () -> service.createDraft(rules("v-dup")));
        assertThrows(RuleVersionNotFoundException.class, () -> service.get("missing"));
        service.createDraft(rules("v-unpublished"));
        assertThrows(RuleVersionNotFoundException.class,
                () -> service.getPublished("v-unpublished"));
    }

    @Test
    void sameTrajectoryCanBeReviewedAgainstDifferentVersions() {
        service.createDraft(rules("v-a"));
        service.publish("v-a");
        RuleConfig stricter = new RuleConfig("v-b", 0.5, 5, 3, 0.05, 0.1, 1.0, RuleStatus.DRAFT);
        service.createDraft(stricter);
        service.publish("v-b");

        assertEquals(1.0, service.getPublished("v-a").maxTimeGapSeconds(), 1e-12);
        assertEquals(0.5, service.getPublished("v-b").maxTimeGapSeconds(), 1e-12);
        assertEquals(2, service.list().size());
    }
}
