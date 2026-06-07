package io;

import java.io.*;

/**
 * 实验：验证 FileOutputStream.flush() 是否是 no-op
 *
 * 这个实验验证三个关键事实：
 *   1. FileOutputStream.flush() 是继承自 OutputStream 的空实现
 *   2. FileOutputStream 没有 Java 层缓冲区，write() 直接进入 OS
 *   3. 数据落盘需要 FileDescriptor.sync() 或 FileChannel.force()
 */
public class FlushVerification {
    public static void main(String[] args) throws Exception {

        // ── 实验 1: 源码层面验证 ──
        // 通过反射检查 FileOutputStream 是否 declare 了 flush 方法
        System.out.println("=== 实验 1: FileOutputStream 是否 override 了 flush()？ ===");
        try {
            java.lang.reflect.Method m = FileOutputStream.class.getDeclaredMethod("flush");
            System.out.println("  结论: FileOutputStream 自己声明了 flush() 方法");
        } catch (NoSuchMethodException e) {
            System.out.println("  结论: FileOutputStream 没有声明 flush() 方法");
            System.out.println("  → flush() 继承自 OutputStream（空实现/no-op）");

            // 验证 OutputStream.flush() 确实是空的
            try {
                java.lang.reflect.Method parentFlush = OutputStream.class.getDeclaredMethod("flush");
                System.out.println("  → OutputStream.flush() 签名: " + parentFlush);
                System.out.println("  → 验证: OutputStream.flush() 是空方法体（不能通过反射看到，但 Javadoc 证实）");
            } catch (NoSuchMethodException ex) {
                System.out.println("  → 连 OutputStream 也没有？这不可能");
            }
        }

        // ── 实验 2: 行为层面验证 ──
        System.out.println("\n=== 实验 2: BufferedOutputStream vs FileOutputStream ===");

        File f1 = File.createTempFile("flush_test_1_", ".txt");
        File f2 = File.createTempFile("flush_test_2_", ".txt");
        f1.deleteOnExit();
        f2.deleteOnExit();

        // 2a: FileOutputStream — 不调用 flush()，文件有内容吗？
        FileOutputStream fos = new FileOutputStream(f1);
        fos.write("Hello from FileOutputStream (no flush)".getBytes());
        // 不调用 flush()
        fos.close(); // close 会释放 fd，但在此之前数据已经通过 write() 进入 OS

        byte[] data1 = java.nio.file.Files.readAllBytes(f1.toPath());
        System.out.println("  FileOutputStream(无flush) → 文件大小: " + data1.length + " 字节");
        System.out.println("  内容: " + new String(data1).trim());
        System.out.println("  解释: FileOutputStream 无 Java 缓冲区, write() 直接进入 OS Page Cache");

        // 2b: BufferedOutputStream — 不调用 flush()，文件有内容吗？
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(f2));
        bos.write("Hello from BufferedOutputStream (no flush)".getBytes());
        // 不调 flush(), 不调 close()

        byte[] data2 = java.nio.file.Files.readAllBytes(f2.toPath());
        System.out.println("\n  BufferedOutputStream(无flush) → 文件大小: " + data2.length + " 字节");
        if (data2.length == 0) {
            System.out.println("  解释: 数据残留在 BufferedOutputStream 的 8192 字节内部缓冲区中！");
        }
        bos.close();

        // ── 实验 3: 真正刷盘的手段 ──
        System.out.println("\n=== 实验 3: 刷盘手段对比 ===");
        System.out.println("  FileOutputStream.flush()    → no-op（继承自 OutputStream 空实现）");
        System.out.println("  BufferedOutputStream.flush() → 将 JVM 缓冲区推入 OS Page Cache");
        System.out.println("  FileDescriptor.sync()       → fsync() 系统调用，阻塞直到数据写入磁盘");
        System.out.println("  FileChannel.force(false)    → fdatasync()，仅数据不刷新元数据");
        System.out.println("  FileChannel.force(true)     → fsync()，数据 + 元数据都刷新");
    }
}
