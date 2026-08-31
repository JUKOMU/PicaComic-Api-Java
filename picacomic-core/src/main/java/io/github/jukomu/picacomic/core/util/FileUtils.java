package io.github.jukomu.picacomic.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

/**
 * 文件名边界与原子落盘辅助方法。
 */
public final class FileUtils {

    private static final int MAX_SEGMENT_LENGTH = 255;

    private FileUtils() {
    }

    /**
     * 将一个远端元数据值限制为单个、跨平台可用的路径 segment。
     */
    public static String safePathSegment(String input) {
        return sanitizeFilename(input, "_");
    }

    public static String sanitizeFilename(String input, String replacement) {
        Objects.requireNonNull(replacement, "Replacement cannot be null");
        if (input == null || input.isBlank()) {
            return "unknown_filename";
        }

        StringBuilder output = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (isIllegalFilenameCharacter(ch)) {
                output.append(replacement);
            } else {
                output.append(ch);
            }
        }

        String sanitized = output.toString().strip();
        int end = sanitized.length();
        while (end > 0) {
            char last = sanitized.charAt(end - 1);
            if (last == '.' || last == ' ') {
                end--;
            } else {
                break;
            }
        }
        sanitized = sanitized.substring(0, end);
        if (sanitized.isEmpty()) {
            return "unknown_filename";
        }

        if (isWindowsReservedBasename(sanitized)) {
            sanitized = "_" + sanitized;
        }

        if (sanitized.length() > MAX_SEGMENT_LENGTH) {
            sanitized = sanitized.substring(0, MAX_SEGMENT_LENGTH).strip();
            int safeEnd = sanitized.length();
            while (safeEnd > 0) {
                char last = sanitized.charAt(safeEnd - 1);
                if (last == '.' || last == ' ') {
                    safeEnd--;
                } else {
                    break;
                }
            }
            sanitized = sanitized.substring(0, safeEnd);
        }
        return sanitized.isEmpty() ? "unknown_filename" : sanitized;
    }

    public static String sanitizeFilename(String input) {
        return sanitizeFilename(input, "_");
    }

    /**
     * 创建与目标同目录的唯一临时文件。调用者负责在 finally 中删除它。
     */
    public static Path createAtomicTemp(Path target) throws IOException {
        Path normalizedTarget = normalizeAbsolute(target);
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("Target must have a parent directory");
        }
        Files.createDirectories(parent);
        return Files.createTempFile(parent, ".pica-", ".part");
    }

    /**
     * 仅使用 ATOMIC_MOVE，文件系统不支持时将 AtomicMoveNotSupportedException 原样交给调用者。
     */
    public static void moveAtomically(Path temporary, Path target) throws IOException {
        Objects.requireNonNull(temporary, "Temporary path cannot be null");
        Path normalizedTarget = normalizeAbsolute(target);
        Files.move(temporary, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 原始失败由调用者保留；清理失败不能覆盖主异常。
        }
    }

    /**
     * 将安全 segment 追加到 caller 明确提供的 base，并验证 lexical containment。
     */
    public static Path resolveDescendant(Path base, String remoteSegment) {
        Objects.requireNonNull(base, "Base path cannot be null");
        Path normalizedBase = normalizeAbsolute(base);
        Path candidate = normalizedBase.resolve(safePathSegment(remoteSegment)).normalize();
        if (!candidate.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("Resolved path escaped its base directory");
        }
        return candidate;
    }

    public static Path normalizeAbsolute(Path path) {
        return Objects.requireNonNull(path, "Path cannot be null").toAbsolutePath().normalize();
    }

    private static boolean isIllegalFilenameCharacter(char ch) {
        return ch == '\\' || ch == '/' || ch == ':' || ch == '*' || ch == '?'
                || ch == '"' || ch == '<' || ch == '>' || ch == '|'
                || Character.isISOControl(ch);
    }

    private static boolean isWindowsReservedBasename(String value) {
        String basename = value;
        int dot = basename.indexOf('.');
        if (dot >= 0) {
            basename = basename.substring(0, dot);
        }
        String upper = basename.toUpperCase(Locale.ROOT);
        return "CON".equals(upper) || "PRN".equals(upper) || "AUX".equals(upper)
                || "NUL".equals(upper)
                || (upper.length() == 4
                && (upper.startsWith("COM") || upper.startsWith("LPT"))
                && upper.charAt(3) >= '1' && upper.charAt(3) <= '9');
    }
}
