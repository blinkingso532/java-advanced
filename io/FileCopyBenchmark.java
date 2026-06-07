package io;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 文件复制性能对比：传统 BIO vs NIO Buffer vs NIO 零拷贝
 *
 * 学习目标：
 *   1. 理解三种 IO 方式的实现差异
 *   2. 直观理解零拷贝的性能优势
 *   3. 掌握微基准测试的基本方法（注意 JVM 预热）
 */
public class FileCopyBenchmark {

    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 5;
    private static final int BUFFER_SIZE = 8192; // 8KB

    public static void main(String[] args) throws IOException {
        // 生成测试文件 (~100MB)
        File testFile = new File("test_benchmark.dat");
        if (!testFile.exists()) {
            System.out.println("生成 100MB 测试文件...");
            generateTestFile(testFile, 100 * 1024 * 1024);
        }

        File destFile = new File("test_dest.dat");

        System.out.println("\n========== 预热 (Warmup) ==========");
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            copyWithBIO(testFile, destFile);
            copyWithNIO(testFile, destFile);
            copyWithZeroCopy(testFile, destFile);
        }

        System.out.println("\n========== 正式测试 ==========");
        System.out.printf("%-25s %12s %12s %12s%n", "方式", "平均耗时(ms)", "吞吐量(MB/s)", "最小耗时(ms)");
        System.out.println("─".repeat(70));

        benchmark("传统 BIO BufferedStream", () -> copyWithBIO(testFile, destFile));
        benchmark("NIO FileChannel + Buffer", () -> copyWithNIO(testFile, destFile));
        benchmark("NIO transferTo (零拷贝)", () -> copyWithZeroCopy(testFile, destFile));

        // 清理
        testFile.delete();
        destFile.delete();
    }

    // ────────── 三种复制实现 ──────────

    /**
     * 方式一：传统 BIO — BufferedInputStream + BufferedOutputStream
     * 数据流：磁盘 → 内核缓冲区 → 用户空间(JVM堆) → 内核缓冲区 → 磁盘
     * 拷贝次数：4次（2次 DMA + 2次 CPU）
     */
    static void copyWithBIO(File src, File dest) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    /**
     * 方式二：NIO FileChannel + ByteBuffer
     * 数据流：同 BIO，但 Channel 比 Stream 更高效
     * 如果使用 DirectBuffer，可以减少一次从 JVM 堆到本地堆的拷贝
     */
    static void copyWithNIO(File src, File dest) throws IOException {
        try (FileChannel srcChannel = FileChannel.open(src.toPath());
             FileChannel destChannel = FileChannel.open(dest.toPath(),
                     java.nio.file.StandardOpenOption.CREATE,
                     java.nio.file.StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_SIZE);
            while (srcChannel.read(buf) != -1) {
                buf.flip();
                destChannel.write(buf);
                buf.clear();
            }
        }
    }

    /**
     * 方式三：零拷贝 transferTo
     * 数据流：磁盘 → 内核缓冲区 → 磁盘（DMA gather 操作）
     * 拷贝次数：2次（全部由 DMA 完成，CPU 不参与数据拷贝）
     *
     * Linux 底层调用 sendfile() 系统调用。
     * 数据不经过用户空间，这是性能优势的根本原因。
     */
    static void copyWithZeroCopy(File src, File dest) throws IOException {
        try (FileChannel srcChannel = FileChannel.open(src.toPath());
             FileChannel destChannel = FileChannel.open(dest.toPath(),
                     java.nio.file.StandardOpenOption.CREATE,
                     java.nio.file.StandardOpenOption.WRITE)) {
            long position = 0;
            long size = srcChannel.size();
            // transferTo 单次最多传输 2GB-1，需要循环
            while (position < size) {
                position += srcChannel.transferTo(position, size - position, destChannel);
            }
        }
    }

    // ────────── 辅助方法 ──────────

    private static void generateTestFile(File file, long size) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buf = new byte[8192];
            long written = 0;
            while (written < size) {
                int toWrite = (int) Math.min(buf.length, size - written);
                fos.write(buf, 0, toWrite);
                written += toWrite;
            }
        }
    }

    @FunctionalInterface
    interface IOCopyTask {
        void run() throws IOException;
    }

    private static void benchmark(String name, IOCopyTask task) {
        long minTime = Long.MAX_VALUE;
        long totalTime = 0;

        for (int i = 0; i < MEASURE_ROUNDS; i++) {
            try {
                long start = System.nanoTime();
                task.run();
                long elapsed = (System.nanoTime() - start) / 1_000_000; // ms
                totalTime += elapsed;
                if (elapsed < minTime) minTime = elapsed;
            } catch (IOException e) {
                System.out.printf("%-25s ERROR: %s%n", name, e.getMessage());
                return;
            }
        }

        long avgMs = totalTime / MEASURE_ROUNDS;
        double throughputMBps = 100.0 / (avgMs / 1000.0); // 100MB / seconds

        System.out.printf("%-25s %10d ms %10.1f MB/s %10d ms%n",
                name, avgMs, throughputMBps, minTime);
    }
}
