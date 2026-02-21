package io.github.jukomu.picacomic.core.util;

import java.util.regex.Pattern;

/**
 * @author JUKOMU
 * @Description: 内部文件操作相关的工具类
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class FileUtils {

    private FileUtils() {
        // 防止实例化
    }

    /**
     * 定义了一个匹配在主流操作系统（特别是Windows）中非法的或不推荐的文件名字符的正则表达式
     * 包括： \ / : * ? " < > | 以及所有控制字符（例如换行、制表符）
     * \\u0000 是 null 字符。
     */
    private static final Pattern ILLEGAL_CHARACTERS_PATTERN = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");

    /**
     * "净化"一个字符串，使其可以安全地用作文件名或文件夹名。
     * 它会移除所有在主流操作系统中非法的字符。
     *
     * @param input       原始的、可能包含非法字符的字符串。
     * @param replacement 用于替换非法字符的字符串。不能为空。
     * @return 净化后的字符串。
     */
    public static String sanitizeFilename(String input, String replacement) {
        if (input == null || input.isBlank()) {
            return "unknown_filename"; // 防止空名
        }

        String sanitized = ILLEGAL_CHARACTERS_PATTERN.matcher(input).replaceAll(replacement);

        sanitized = sanitized.trim();
        sanitized = sanitized.replaceAll("[. ]+$", "");

        if (isReservedName(sanitized)) {
            sanitized = "_" + sanitized;
        }

        if (sanitized.isEmpty()) {
            return "unknown_filename";
        }

        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 255);
            sanitized = sanitized.trim().replaceAll("[. ]+$", "");
        }

        return sanitized;
    }

    /**
     * 检查是否为 Windows 保留文件名
     */
    private static boolean isReservedName(String name) {
        // Windows 保留文件名（不区分大小写）
        String upper = name.toUpperCase();
        return "CON".equals(upper) || "PRN".equals(upper) || "AUX".equals(upper) ||
                "NUL".equals(upper) ||
                (upper.length() == 4 && (upper.startsWith("COM") || upper.startsWith("LPT")) && Character.isDigit(upper.charAt(3)));
    }

    /**
     * "净化"一个字符串，使其可以安全地用作文件名或文件夹名。
     * 非法字符将被替换为下划线 "_"。
     *
     * @param input 原始的、可能包含非法字符的字符串。
     * @return 净化后的字符串。
     */
    public static String sanitizeFilename(String input) {
        return sanitizeFilename(input, "_");
    }
}
