package io;

import java.nio.ByteBuffer;

/**
 * 核心概念演示：ByteBuffer 的 position / limit / capacity 三指针
 *
 * 这是 NIO 最基本也最容易混淆的知识点。
 * 运行这个 main 方法，观察每一步后 Buffer 的状态变化。
 */
public class BufferDemo {

    public static void main(String[] args) {
        System.out.println("========== 1. 分配缓冲区 ==========");
        ByteBuffer buf = ByteBuffer.allocate(10);
        printState(buf, "allocate(10)");

        System.out.println("\n========== 2. 写入数据 ==========");
        buf.put((byte) 'H');
        buf.put((byte) 'e');
        buf.put((byte) 'l');
        buf.put((byte) 'l');
        buf.put((byte) 'o');
        printState(buf, "put 5 bytes");

        System.out.println("\n========== 3. flip()：写模式 → 读模式 ==========");
        buf.flip();
        printState(buf, "flip()");
        System.out.println("  flip() 做了什么？limit = position(5), position = 0");

        System.out.println("\n========== 4. 读取数据 ==========");
        System.out.print("  读取内容: ");
        while (buf.hasRemaining()) {
            System.out.print((char) buf.get());
        }
        System.out.println();
        printState(buf, "读完所有字节后");

        System.out.println("\n========== 5. clear() vs compact() 对比 ==========");
        testClearVsCompact();

        System.out.println("\n========== 6. mark() 和 reset() ==========");
        testMarkReset();

        System.out.println("\n========== 7. 直接缓冲区 vs 堆缓冲区 ==========");
        testDirectVsHeap();
    }

    private static void testClearVsCompact() {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.put((byte) 'A');
        buf.put((byte) 'B');
        buf.put((byte) 'C');
        buf.put((byte) 'D');
        buf.put((byte) 'E');
        buf.flip(); // 准备读

        buf.get(); // 读 A
        buf.get(); // 读 B
        printState(buf, "读了 2 个字节后（剩余 CDE 未读）");

        // === clear() ===
        ByteBuffer bufForClear = buf.duplicate(); // 快照当前状态
        bufForClear.clear();
        printState(bufForClear, "clear() 后 — 丢失未读数据 CDE，position 回到 0");

        // === compact() ===
        buf.compact();
        printState(buf, "compact() 后 — 保留未读数据 CDE 并移到开头");
    }

    private static void testMarkReset() {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.put("ABCDEFGHIJ".getBytes());
        buf.flip();

        buf.get(); // A
        buf.get(); // B
        buf.mark();  // 在 C 的位置打标记
        buf.get(); // C
        buf.get(); // D
        buf.get(); // E
        printState(buf, "读了 5 个字节，mark 在索引 2");

        buf.reset();
        printState(buf, "reset() 后 —— position 回到 mark(2)");
        System.out.print("  从 reset 后读取: ");
        while (buf.hasRemaining()) {
            System.out.print((char) buf.get());
        }
        System.out.println();
    }

    private static void testDirectVsHeap() {
        // 堆缓冲区
        ByteBuffer heapBuf = ByteBuffer.allocate(1024);
        System.out.println("  Heap Buffer:   isDirect=" + heapBuf.isDirect()
                + ", hasArray=" + heapBuf.hasArray());

        // 直接缓冲区（堆外内存）
        ByteBuffer directBuf = ByteBuffer.allocateDirect(1024);
        System.out.println("  Direct Buffer: isDirect=" + directBuf.isDirect()
                + ", hasArray=" + directBuf.hasArray());
        System.out.println("  注意：Direct Buffer 的 array() 会抛 UnsupportedOperationException");
    }

    private static void printState(ByteBuffer buf, String label) {
        System.out.printf("  [%-16s] position=%2d  limit=%2d  capacity=%2d  remaining=%2d%n",
                label, buf.position(), buf.limit(), buf.capacity(), buf.remaining());
    }
}
