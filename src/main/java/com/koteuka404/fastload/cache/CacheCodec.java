package com.koteuka404.fastload.cache;

import net.minecraftforge.fml.common.discovery.asm.ModAnnotation;
import org.objectweb.asm.Type;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CacheCodec {
    private static final int MAGIC = 0x464C4331; // FLC1
    private static final int FORMAT_VERSION = 1;

    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CLASSES = 500_000;
    private static final int MAX_ASM_ENTRIES = 1_000_000;
    private static final int MAX_MODS = 10_000;
    private static final int MAX_COLLECTION_SIZE = 100_000;
    private static final int MAX_DEPTH = 32;

    private static final byte NULL = 0;
    private static final byte STRING = 1;
    private static final byte BOOLEAN = 2;
    private static final byte BYTE = 3;
    private static final byte SHORT = 4;
    private static final byte INTEGER = 5;
    private static final byte LONG = 6;
    private static final byte FLOAT = 7;
    private static final byte DOUBLE = 8;
    private static final byte CHARACTER = 9;
    private static final byte ASM_TYPE = 10;
    private static final byte ENUM_HOLDER = 11;
    private static final byte LIST = 12;
    private static final byte MAP = 13;
    private static final byte BYTE_ARRAY = 14;
    private static final byte BOOLEAN_ARRAY = 15;
    private static final byte SHORT_ARRAY = 16;
    private static final byte CHAR_ARRAY = 17;
    private static final byte INT_ARRAY = 18;
    private static final byte LONG_ARRAY = 19;
    private static final byte FLOAT_ARRAY = 20;
    private static final byte DOUBLE_ARRAY = 21;
    private static final byte STRING_ARRAY = 22;
    private static final byte TYPE_ARRAY = 23;

    private CacheCodec() {
    }

    static CachedJar read(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return null;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Invalid FastLoad cache magic");
            }
            if (in.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported FastLoad cache version");
            }

            CachedJar cache = new CachedJar();
            cache.sourceSize = in.readLong();
            cache.sourceModified = in.readLong();
            cache.sourceSha256 = readByteArray(in, 64);
            if (cache.sourceSha256 != null && cache.sourceSha256.length != 32) {
                throw new IOException("Invalid SHA-256 length in FastLoad cache");
            }
            cache.forgeVersion = readString(in);
            cache.containerSignature = readString(in);
            cache.scanNanos = in.readLong();

            int classCount = checkedCount(in.readInt(), MAX_CLASSES, "class count");
            for (int i = 0; i < classCount; i++) {
                cache.classEntries.add(readRequiredString(in));
            }

            int asmCount = checkedCount(in.readInt(), MAX_ASM_ENTRIES, "ASM entry count");
            for (int i = 0; i < asmCount; i++) {
                CachedJar.CachedAsmData data = new CachedJar.CachedAsmData();
                data.annotationName = readRequiredString(in);
                data.className = readRequiredString(in);
                data.objectName = readString(in);
                data.annotationInfo = readStringMap(in, 0);
                cache.asmData.add(data);
            }

            int modCount = checkedCount(in.readInt(), MAX_MODS, "mod count");
            for (int i = 0; i < modCount; i++) {
                CachedJar.CachedMod mod = new CachedJar.CachedMod();
                mod.annotationName = readRequiredString(in);
                mod.className = readRequiredString(in);
                mod.descriptor = readStringMap(in, 0);
                mod.classVersion = in.readInt();
                cache.mods.add(mod);
            }

            if (in.read() != -1) {
                throw new IOException("Unexpected trailing data in FastLoad cache");
            }
            return cache;
        } catch (EOFException e) {
            throw new IOException("Truncated FastLoad cache", e);
        }
    }

    static void writeAtomic(File target, CachedJar cache) throws IOException {
        File parent = target.getParentFile();
        if (parent == null) {
            throw new IOException("Cache target has no parent directory");
        }
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create cache directory: " + parent);
        }

        File temp = File.createTempFile(target.getName(), ".tmp", parent);
        boolean moved = false;
        try {
            try (FileOutputStream fos = new FileOutputStream(temp);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeLong(cache.sourceSize);
                out.writeLong(cache.sourceModified);
                writeByteArray(out, cache.sourceSha256);
                writeString(out, cache.forgeVersion);
                writeString(out, cache.containerSignature);
                out.writeLong(cache.scanNanos);

                checkedWriteCount(cache.classEntries.size(), MAX_CLASSES, "class count");
                out.writeInt(cache.classEntries.size());
                for (String classEntry : cache.classEntries) {
                    writeRequiredString(out, classEntry);
                }

                checkedWriteCount(cache.asmData.size(), MAX_ASM_ENTRIES, "ASM entry count");
                out.writeInt(cache.asmData.size());
                for (CachedJar.CachedAsmData data : cache.asmData) {
                    writeRequiredString(out, data.annotationName);
                    writeRequiredString(out, data.className);
                    writeString(out, data.objectName);
                    writeStringMap(out, data.annotationInfo, 0);
                }

                checkedWriteCount(cache.mods.size(), MAX_MODS, "mod count");
                out.writeInt(cache.mods.size());
                for (CachedJar.CachedMod mod : cache.mods) {
                    writeRequiredString(out, mod.annotationName);
                    writeRequiredString(out, mod.className);
                    writeStringMap(out, mod.descriptor, 0);
                    out.writeInt(mod.classVersion);
                }
                out.flush();
                fos.getFD().sync();
            }

            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static void writeStringMap(DataOutputStream out, Map<String, Object> map, int depth) throws IOException {
        ensureDepth(depth);
        if (map == null) {
            out.writeInt(-1);
            return;
        }
        checkedWriteCount(map.size(), MAX_COLLECTION_SIZE, "map size");
        out.writeInt(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            writeRequiredString(out, entry.getKey());
            writeValue(out, entry.getValue(), depth + 1);
        }
    }

    private static Map<String, Object> readStringMap(DataInputStream in, int depth) throws IOException {
        ensureDepth(depth);
        int size = in.readInt();
        if (size == -1) {
            return null;
        }
        checkedCount(size, MAX_COLLECTION_SIZE, "map size");
        Map<String, Object> map = new HashMap<String, Object>(Math.max(16, size * 2));
        for (int i = 0; i < size; i++) {
            String key = readRequiredString(in);
            map.put(key, readValue(in, depth + 1));
        }
        return map;
    }

    private static void writeValue(DataOutputStream out, Object value, int depth) throws IOException {
        ensureDepth(depth);
        if (value == null) {
            out.writeByte(NULL);
        } else if (value instanceof String) {
            out.writeByte(STRING);
            writeRequiredString(out, (String) value);
        } else if (value instanceof Boolean) {
            out.writeByte(BOOLEAN);
            out.writeBoolean((Boolean) value);
        } else if (value instanceof Byte) {
            out.writeByte(BYTE);
            out.writeByte((Byte) value);
        } else if (value instanceof Short) {
            out.writeByte(SHORT);
            out.writeShort((Short) value);
        } else if (value instanceof Integer) {
            out.writeByte(INTEGER);
            out.writeInt((Integer) value);
        } else if (value instanceof Long) {
            out.writeByte(LONG);
            out.writeLong((Long) value);
        } else if (value instanceof Float) {
            out.writeByte(FLOAT);
            out.writeFloat((Float) value);
        } else if (value instanceof Double) {
            out.writeByte(DOUBLE);
            out.writeDouble((Double) value);
        } else if (value instanceof Character) {
            out.writeByte(CHARACTER);
            out.writeChar((Character) value);
        } else if (value instanceof Type) {
            out.writeByte(ASM_TYPE);
            writeRequiredString(out, ((Type) value).getDescriptor());
        } else if (value instanceof ModAnnotation.EnumHolder) {
            ModAnnotation.EnumHolder enumHolder = (ModAnnotation.EnumHolder) value;
            out.writeByte(ENUM_HOLDER);
            writeRequiredString(out, enumHolder.getDesc());
            writeRequiredString(out, enumHolder.getValue());
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            checkedWriteCount(list.size(), MAX_COLLECTION_SIZE, "list size");
            out.writeByte(LIST);
            out.writeInt(list.size());
            for (Object item : list) {
                writeValue(out, item, depth + 1);
            }
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> rawMap = (Map<Object, Object>) value;
            checkedWriteCount(rawMap.size(), MAX_COLLECTION_SIZE, "map size");
            out.writeByte(MAP);
            out.writeInt(rawMap.size());
            for (Map.Entry<Object, Object> entry : rawMap.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IOException("Unsupported annotation map key type: " + entry.getKey());
                }
                writeRequiredString(out, (String) entry.getKey());
                writeValue(out, entry.getValue(), depth + 1);
            }
        } else if (value instanceof byte[]) {
            out.writeByte(BYTE_ARRAY);
            writeByteArray(out, (byte[]) value);
        } else if (value instanceof boolean[]) {
            boolean[] values = (boolean[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "boolean array size");
            out.writeByte(BOOLEAN_ARRAY);
            out.writeInt(values.length);
            for (boolean item : values) out.writeBoolean(item);
        } else if (value instanceof short[]) {
            short[] values = (short[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "short array size");
            out.writeByte(SHORT_ARRAY);
            out.writeInt(values.length);
            for (short item : values) out.writeShort(item);
        } else if (value instanceof char[]) {
            char[] values = (char[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "char array size");
            out.writeByte(CHAR_ARRAY);
            out.writeInt(values.length);
            for (char item : values) out.writeChar(item);
        } else if (value instanceof int[]) {
            int[] values = (int[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "int array size");
            out.writeByte(INT_ARRAY);
            out.writeInt(values.length);
            for (int item : values) out.writeInt(item);
        } else if (value instanceof long[]) {
            long[] values = (long[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "long array size");
            out.writeByte(LONG_ARRAY);
            out.writeInt(values.length);
            for (long item : values) out.writeLong(item);
        } else if (value instanceof float[]) {
            float[] values = (float[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "float array size");
            out.writeByte(FLOAT_ARRAY);
            out.writeInt(values.length);
            for (float item : values) out.writeFloat(item);
        } else if (value instanceof double[]) {
            double[] values = (double[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "double array size");
            out.writeByte(DOUBLE_ARRAY);
            out.writeInt(values.length);
            for (double item : values) out.writeDouble(item);
        } else if (value instanceof String[]) {
            String[] values = (String[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "string array size");
            out.writeByte(STRING_ARRAY);
            out.writeInt(values.length);
            for (String item : values) writeRequiredString(out, item);
        } else if (value instanceof Type[]) {
            Type[] values = (Type[]) value;
            checkedWriteCount(values.length, MAX_COLLECTION_SIZE, "type array size");
            out.writeByte(TYPE_ARRAY);
            out.writeInt(values.length);
            for (Type item : values) writeRequiredString(out, item.getDescriptor());
        } else if (value.getClass().isArray()) {
            throw new IOException("Unsupported annotation array type: " + value.getClass().getName());
        } else {
            throw new IOException("Unsupported annotation value type: " + value.getClass().getName());
        }
    }

    private static Object readValue(DataInputStream in, int depth) throws IOException {
        ensureDepth(depth);
        byte tag = in.readByte();
        switch (tag) {
            case NULL: return null;
            case STRING: return readRequiredString(in);
            case BOOLEAN: return in.readBoolean();
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INTEGER: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case CHARACTER: return in.readChar();
            case ASM_TYPE: return Type.getType(readRequiredString(in));
            case ENUM_HOLDER: return new ModAnnotation.EnumHolder(readRequiredString(in), readRequiredString(in));
            case LIST: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "list size");
                List<Object> list = new ArrayList<Object>(size);
                for (int i = 0; i < size; i++) list.add(readValue(in, depth + 1));
                return list;
            }
            case MAP: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "map size");
                Map<String, Object> map = new HashMap<String, Object>(Math.max(16, size * 2));
                for (int i = 0; i < size; i++) map.put(readRequiredString(in), readValue(in, depth + 1));
                return map;
            }
            case BYTE_ARRAY: return readByteArray(in, MAX_COLLECTION_SIZE);
            case BOOLEAN_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "boolean array size");
                boolean[] values = new boolean[size];
                for (int i = 0; i < size; i++) values[i] = in.readBoolean();
                return values;
            }
            case SHORT_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "short array size");
                short[] values = new short[size];
                for (int i = 0; i < size; i++) values[i] = in.readShort();
                return values;
            }
            case CHAR_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "char array size");
                char[] values = new char[size];
                for (int i = 0; i < size; i++) values[i] = in.readChar();
                return values;
            }
            case INT_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "int array size");
                int[] values = new int[size];
                for (int i = 0; i < size; i++) values[i] = in.readInt();
                return values;
            }
            case LONG_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "long array size");
                long[] values = new long[size];
                for (int i = 0; i < size; i++) values[i] = in.readLong();
                return values;
            }
            case FLOAT_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "float array size");
                float[] values = new float[size];
                for (int i = 0; i < size; i++) values[i] = in.readFloat();
                return values;
            }
            case DOUBLE_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "double array size");
                double[] values = new double[size];
                for (int i = 0; i < size; i++) values[i] = in.readDouble();
                return values;
            }
            case STRING_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "string array size");
                String[] values = new String[size];
                for (int i = 0; i < size; i++) values[i] = readRequiredString(in);
                return values;
            }
            case TYPE_ARRAY: {
                int size = checkedCount(in.readInt(), MAX_COLLECTION_SIZE, "type array size");
                Type[] values = new Type[size];
                for (int i = 0; i < size; i++) values[i] = Type.getType(readRequiredString(in));
                return values;
            }
            default:
                throw new IOException("Unknown annotation value tag: " + tag);
        }
    }

    private static void writeByteArray(DataOutputStream out, byte[] data) throws IOException {
        if (data == null) {
            out.writeInt(-1);
            return;
        }
        checkedWriteCount(data.length, MAX_COLLECTION_SIZE, "byte array size");
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readByteArray(DataInputStream in, int maxLength) throws IOException {
        int length = in.readInt();
        if (length == -1) {
            return null;
        }
        checkedCount(length, maxLength, "byte array size");
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private static void writeRequiredString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            throw new IOException("Required string is null");
        }
        writeString(out, value);
    }

    private static String readRequiredString(DataInputStream in) throws IOException {
        String value = readString(in);
        if (value == null) {
            throw new IOException("Required string is null");
        }
        return value;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("String exceeds cache limit: " + bytes.length);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length == -1) {
            return null;
        }
        checkedCount(length, MAX_STRING_BYTES, "string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int checkedCount(int value, int max, String name) throws IOException {
        if (value < 0 || value > max) {
            throw new IOException("Invalid " + name + ": " + value);
        }
        return value;
    }

    private static void checkedWriteCount(int value, int max, String name) throws IOException {
        if (value < 0 || value > max) {
            throw new IOException("Refusing to write invalid " + name + ": " + value);
        }
    }

    private static void ensureDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Annotation structure exceeds maximum nesting depth");
        }
    }
}
