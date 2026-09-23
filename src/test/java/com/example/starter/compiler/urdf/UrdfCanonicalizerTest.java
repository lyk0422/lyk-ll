package com.example.starter.compiler.urdf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultUrdfCanonicalizer} 的确定性测试。
 */
class UrdfCanonicalizerTest {

    private final DefaultUrdfCanonicalizer canonicalizer = new DefaultUrdfCanonicalizer();

    private String normalize(String xml) {
        return new String(canonicalizer.canonicalize(xml), StandardCharsets.UTF_8);
    }

    @Test
    void identicalModelsProduceIdenticalBytesDespiteFormatting() {
        String withDeclaration = """
                <?xml version="1.0"?>
                <robot name="r" foo="1">
                  <link name="a">
                    <visual>
                      <geometry><box size="1 2 3"/></geometry>
                    </visual>
                  </link>
                </robot>
                """;
        String compact = """
                <robot foo="1" name="r">
                  <link name="a"><visual><geometry><box size="1 2 3"/></geometry></visual></link>
                </robot>
                """;

        assertThat(normalize(withDeclaration)).isEqualTo(normalize(compact));
    }

    @Test
    void attributesAreSortedAndNamespacesRemoved() {
        String xml = """
                <robot name="r" xmlns:xacro="http://x" zzz="2" aaa="1">
                  <link name="l"/>
                </robot>
                """;
        String text = normalize(xml);

        assertThat(text).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(text).contains("<robot aaa=\"1\" name=\"r\" zzz=\"2\">");
        assertThat(text).doesNotContain("xmlns:xacro");
        assertThat(text).contains("<link name=\"l\"/>");
        assertThat(text).doesNotContain("\r");
    }

    @Test
    void emptyElementsAreSelfClosed() {
        String text = normalize("<robot name=\"r\"><link name=\"a\"></link></robot>");
        assertThat(text).contains("<link name=\"a\"/>");
    }

    @Test
    void sha256IsStableAcrossFormatterDifferences() throws Exception {
        String one = "<robot name=\"r\"><link name=\"a\"/></robot>";
        String two = """
                <?xml version="1.0"?>
                <robot name="r">
                    <link name="a">
                    </link>
                </robot>
                """;

        byte[] canonicalOne = canonicalizer.canonicalize(one);
        byte[] canonicalTwo = canonicalizer.canonicalize(two);
        String hashOne = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalOne));
        String hashTwo = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalTwo));

        assertThat(hashOne).isEqualTo(hashTwo);
        assertThat(canonicalOne).isEqualTo(canonicalTwo);
    }

    @Test
    void differentAttributeValueProducesDifferentBytes() {
        String one = normalize("<robot name=\"r\"><link name=\"a\"/></robot>");
        String two = normalize("<robot name=\"r\"><link name=\"b\"/></robot>");
        assertThat(one).isNotEqualTo(two);
    }
}
