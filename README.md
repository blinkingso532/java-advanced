# Java 进阶学习笔记

## 目录索引

### IO/NIO
- **[Java IO/NIO 掌握指南](Java-IO-NIO-掌握指南.md)** — 学习路线、实践项目、测验、面试题
- 代码示例（包 `io/`）：
  - `BufferDemo.java` — NIO Buffer 三指针操作演示
  - `FlushVerification.java` — 反射验证 FileOutputStream.flush() 是 no-op
  - `FileCopyBenchmark.java` — 传统BIO vs NIO vs 零拷贝性能对比
  - `EchoServerNIO.java` — Selector 多路复用 Echo Server
  - `ChatServerNIO.java` — 多客户端聊天室（实践项目）
  - `LargeFileParser.java` — MappedByteBuffer 大文件解析
  - `FileWatcherDemo.java` — WatchService 文件系统监控

### JVM
- [JVM基本原理.md](JVM基本原理.md)
- [GC垃圾回收机制.md](GC垃圾回收机制.md)
- [JVM调优.md](JVM调优.md)
- [jstat.md](jstat.md)
- [oom.md](oom.md)
- 代码示例（根目录）：
  - `OOMTest.java` — OOM 场景模拟
  - `VirtualThreadTest.java` — 虚拟线程测试

### 并发编程
- `UserService.java` — 待补充文档
