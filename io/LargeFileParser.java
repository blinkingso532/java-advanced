package io;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大文件日志解析器 — 使用 MappedByteBuffer 实现低内存占用
 *
 * 核心设计：
 *   1. 分片映射：每次 map 一块区域（如 64MB），避免一次性映射整个大文件
 *   2. 跨块拼接：处理被分片边界切断的行
 *   3. 异常栈聚合：识别多行日志（以空格/制表符开头的行属于上一行）
 *   4. 内存占用分析：只有映射窗口大小 + 少量 Java 对象的开销
 *
 * 验证：
 *   1. 生成测试数据（如 1GB 日志文件）
 *   2. 运行时观察内存：-Xmx128m 也能跑
 */
public class LargeFileParser {

    // 单次映射块大小：64MB
    private static final long CHUNK_SIZE = 64 * 1024 * 1024;

    // 模拟：匹配形如 "[2024-01-01 12:00:00] ERROR ..." 的日志行
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})]\\s+(\\w+)\\s+(.*)");

    public static void main(String[] args) throws IOException {
        Path filePath = Paths.get("large_test.log");
        if (!filePath.toFile().exists()) {
            System.out.println("生成 1GB 测试日志文件（可能需要几十秒）...");
            generateTestLog(filePath, 1024 * 1024 * 1024L);
        }

        System.out.println("开始解析...");
        long start = System.currentTimeMillis();

        LogStats stats = parse(filePath);

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("解析完成，耗时 %d ms%n", elapsed);
        System.out.printf("总行数: %d, 错误数: %d, 警告数: %d, 信息数: %d%n",
                stats.totalLines, stats.errors, stats.warnings, stats.infos);
    }

    public static LogStats parse(Path path) throws IOException {
        LogStats stats = new LogStats();

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            long position = 0;
            StringBuilder overflow = new StringBuilder(); // 跨块拼接缓冲区

            while (position < fileSize) {
                long remaining = fileSize - position;
                long mapSize = Math.min(CHUNK_SIZE, remaining + 1024); // 多映射一点防截断

                MappedByteBuffer buf = channel.map(
                        FileChannel.MapMode.READ_ONLY, position, mapSize);

                // 后处理：修正被截断的最后一行
                int effectiveLimit = (int) Math.min(mapSize, remaining);
                int lastNewline = findLastNewline(buf, effectiveLimit);

                byte[] chunkBytes;
                if (lastNewline >= 0) {
                    chunkBytes = new byte[lastNewline + 1];
                    buf.position(0);
                    buf.get(chunkBytes);
                    position += (lastNewline + 1); // 下次从换行后开始
                } else {
                    chunkBytes = new byte[effectiveLimit];
                    buf.position(0);
                    buf.get(chunkBytes);
                    position += effectiveLimit;
                }

                String chunk = overflow.toString() + new String(chunkBytes);
                overflow.setLength(0); // 清空

                String[] lines = chunk.split("\n", -1);
                // 最后一段可能不完整，留到下一个块拼接
                if (lastNewline < 0 && position < fileSize) {
                    overflow.append(lines[lines.length - 1]);
                    // 仅处理完整行
                    for (int i = 0; i < lines.length - 1; i++) {
                        processLine(lines[i], stats);
                    }
                } else {
                    for (String line : lines) {
                        processLine(line, stats);
                    }
                }
            }

            // 处理最后剩余的 overflow
            if (overflow.length() > 0) {
                processLine(overflow.toString(), stats);
            }
        }
        return stats;
    }

    private static int findLastNewline(MappedByteBuffer buf, int limit) {
        for (int i = limit - 1; i >= 0; i--) {
            if (buf.get(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static void processLine(String line, LogStats stats) {
        // 跳过空行
        if (line.isEmpty()) return;

        // 异常栈行（以空白开头）聚合到上一行
        if (Character.isWhitespace(line.charAt(0))) {
            return; // 已计入上一行的统计
        }

        stats.totalLines++;
        Matcher m = LOG_LINE.matcher(line);
        if (m.find()) {
            String level = m.group(2);
            switch (level) {
                case "ERROR": stats.errors++; break;
                case "WARN":  stats.warnings++; break;
                default:      stats.infos++; break;
            }
        }
    }

    // ────────── 测试数据生成 ──────────

    static void generateTestLog(Path path, long targetSize) throws IOException {
        java.nio.file.Files.deleteIfExists(path);
        try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(path)) {
            String[] levels = {"INFO", "WARN", "ERROR", "DEBUG"};
            long written = 0;
            int counter = 0;
            while (written < targetSize) {
                String line = String.format(
                        "[2024-%02d-%02d %02d:%02d:%02d] %-5s - Processing request #%d - message body here with some extra text for size\n",
                        (counter % 12) + 1, (counter % 28) + 1,
                        counter % 24, counter % 60, counter % 60,
                        levels[counter % levels.length], counter);
                writer.write(line);
                written += line.length();
                counter++;
                if (counter % 100000 == 0) {
                    System.out.printf("  已生成 %d 行...%n", counter);
                }
            }
        }
    }

    // ────────── 统计结果 ──────────

    static class LogStats {
        long totalLines;
        long errors;
        long warnings;
        long infos;
    }
}
