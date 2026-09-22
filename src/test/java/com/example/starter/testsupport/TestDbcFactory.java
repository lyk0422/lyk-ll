package com.example.starter.testsupport;

import com.example.starter.api.dto.DbcImportRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用 DBC 导入请求构造工厂，提供链式 Builder 减少重复样板。
 */
public final class TestDbcFactory {

    private TestDbcFactory() {
    }

    public static ImportBuilder version(String versionName, String requestId) {
        return new ImportBuilder(versionName, requestId);
    }

    /** 导入请求构造器。 */
    public static final class ImportBuilder {
        private final String versionName;
        private final String requestId;
        private final List<DbcImportRequest.MessageDefDto> messages = new ArrayList<>();

        private ImportBuilder(String versionName, String requestId) {
            this.versionName = versionName;
            this.requestId = requestId;
        }

        public MessageBuilder message(long canId, String name, int dlc) {
            return new MessageBuilder(this, canId, name, dlc);
        }

        public DbcImportRequest build() {
            return new DbcImportRequest(versionName, requestId, List.copyOf(messages));
        }

        private void addMessage(DbcImportRequest.MessageDefDto message) {
            messages.add(message);
        }
    }

    /** 报文构造器。 */
    public static final class MessageBuilder {
        private final ImportBuilder parent;
        private final long canId;
        private final String name;
        private final int dlc;
        private final List<DbcImportRequest.SignalDefDto> signals = new ArrayList<>();

        private MessageBuilder(ImportBuilder parent, long canId, String name, int dlc) {
            this.parent = parent;
            this.canId = canId;
            this.name = name;
            this.dlc = dlc;
        }

        public MessageBuilder signal(String name, String byteOrder, int startBit, int bitLength,
                                     boolean signed, double factor, double offset, String unit) {
            return signal(name, byteOrder, startBit, bitLength, signed, factor, offset, unit,
                    "NONE", null, null);
        }

        public MessageBuilder signal(String name, String byteOrder, int startBit, int bitLength,
                                     boolean signed, double factor, double offset, String unit,
                                     String multiplexRole, Long multiplexValue,
                                     Map<Long, String> valueTable) {
            signals.add(new DbcImportRequest.SignalDefDto(name, byteOrder, startBit, bitLength,
                    signed, factor, offset, unit, multiplexRole, multiplexValue,
                    valueTable == null ? null : new HashMap<>(valueTable)));
            return this;
        }

        public ImportBuilder endMessage() {
            parent.addMessage(new DbcImportRequest.MessageDefDto(canId, name, dlc, List.copyOf(signals)));
            return parent;
        }
    }
}
