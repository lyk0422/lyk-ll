package com.example.starter.ros.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RosNamesTest {

    @Test
    void relativeNameIsJoinedWithNamespace() {
        assertThat(RosNames.fullyQualifiedName("/robot", "cmd_vel")).isEqualTo("/robot/cmd_vel");
        assertThat(RosNames.fullyQualifiedName("/robot/arm", "joint/state"))
                .isEqualTo("/robot/arm/joint/state");
    }

    @Test
    void rootNamespaceHasNoDoubleSlash() {
        assertThat(RosNames.fullyQualifiedName("/", "scan")).isEqualTo("/scan");
        assertThat(RosNames.fullyQualifiedName("", "scan")).isEqualTo("/scan");
    }

    @Test
    void absoluteNameIsUsedGlobally() {
        assertThat(RosNames.fullyQualifiedName("/robot", "/global/clock"))
                .isEqualTo("/global/clock");
    }

    @Test
    void redundantSlashesAreCollapsed() {
        assertThat(RosNames.fullyQualifiedName("//robot//", "/service//add"))
                .isEqualTo("/service/add");
        assertThat(RosNames.normalizeNamespace("//ns//sub//")).isEqualTo("/ns/sub");
    }
}
