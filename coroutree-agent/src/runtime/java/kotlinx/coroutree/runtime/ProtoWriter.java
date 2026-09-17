package kotlinx.coroutree.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal protobuf encoder with proto3 semantics: a field holding its zero value is not written.
 * Used on the writer thread only; not thread-safe.
 */
final class ProtoWriter {
    private static final int VARINT = 0;
    private static final int LENGTH_DELIMITED = 2;

    private byte[] buffer = new byte[256];
    private int size;

    /** Scratch writer for nested messages of this one; created on demand, reused afterwards. */
    private ProtoWriter nested;

    void reset() {
        size = 0;
    }

    int size() {
        return size;
    }

    byte[] array() {
        return buffer;
    }

    void int32(int field, int value) {
        int64(field, value);
    }

    void int64(int field, long value) {
        if (value == 0) return;
        tag(field, VARINT);
        varint(value);
    }

    void bool(int field, boolean value) {
        if (!value) return;
        tag(field, VARINT);
        varint(1);
    }

    void string(int field, String value) {
        if (value == null || value.isEmpty()) return;
        bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    void bytes(int field, byte[] value) {
        bytes(field, value, value.length);
    }

    void bytes(int field, byte[] value, int length) {
        tag(field, LENGTH_DELIMITED);
        varint(length);
        raw(value, length);
    }

    /** Packed repeated int32. */
    void packedInt32(int field, int[] values) {
        if (values == null || values.length == 0) return;
        ProtoWriter packed = nested();
        for (int value : values) packed.varint(value);
        bytes(field, packed.buffer, packed.size);
    }

    /**
     * Starts a nested message. Write its fields to the returned writer, then call {@link #endMessage}.
     * Unlike scalars, a message is written even when empty: presence is information.
     */
    ProtoWriter beginMessage() {
        return nested();
    }

    void endMessage(int field, ProtoWriter message) {
        bytes(field, message.buffer, message.size);
    }

    void varint(long value) {
        ensure(10);
        while ((value & ~0x7FL) != 0) {
            buffer[size++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer[size++] = (byte) value;
    }

    void raw(byte[] bytes, int length) {
        ensure(length);
        System.arraycopy(bytes, 0, buffer, size, length);
        size += length;
    }

    private ProtoWriter nested() {
        if (nested == null) nested = new ProtoWriter();
        nested.reset();
        return nested;
    }

    private void tag(int field, int wireType) {
        varint(((long) field << 3) | wireType);
    }

    private void ensure(int extra) {
        if (size + extra > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + extra));
    }
}
