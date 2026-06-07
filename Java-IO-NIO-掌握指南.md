# Java IO/NIO 深度掌握指南

> 从入门到精通的学习路线、实践项目、测验与面试题

---

## 目录

1. [知识体系全景图](#一知识体系全景图)
2. [阶段学习计划](#二阶段学习计划)
3. [实践项目](#三实践项目)
4. [学习成果测验](#四学习成果测验)
5. [常见面试题与解答思路](#五常见面试题与解答思路)
6. [参考资料](#六参考资料)

---

## 一、知识体系全景图

```
Java IO/NIO
├── 1. 传统 IO (java.io) — 阻塞式流模型
│   ├── 字节流: InputStream / OutputStream
│   ├── 字符流: Reader / Writer
│   ├── 装饰器模式: Buffered*, Data*, Object*, Print*
│   ├── 文件操作: File, RandomAccessFile
│   └── 序列化: Serializable, Externalizable
│
├── 2. NIO (java.nio) — 非阻塞面向缓冲区模型
│   ├── Buffer: ByteBuffer, CharBuffer, IntBuffer...
│   ├── Channel: FileChannel, SocketChannel, ServerSocketChannel
│   ├── Selector: 多路复用 (multiplexing)
│   └── 字符集: Charset, CharsetEncoder/Decoder
│
├── 3. NIO.2 (java.nio.file) — 增强文件系统 API
│   ├── Path / Paths / Files
│   ├── FileVisitor / SimpleFileVisitor
│   ├── WatchService: 文件变更监听
│   └── 文件属性: BasicFileAttributes, DosFileAttributes
│
├── 4. 高级主题
│   ├── 零拷贝 (zero-copy): transferTo / transferFrom
│   ├── 内存映射文件: MappedByteBuffer
│   ├── Scatter / Gather
│   ├── 直接缓冲区 vs 堆缓冲区
│   ├── AIO (NIO.2 异步 IO)
│   └── 网络编程模型: Reactor / Proactor
│
└── 5. 实战整合
    ├── 高性能文件复制
    ├── 简易 HTTP 服务器
    ├── 多客户端聊天室
    ├── 大文件日志解析器
    └── 文件系统监控工具
```

---

## 二、阶段学习计划

### 阶段 1：传统 IO 基础（1 周）

**目标**：理解流式 IO 模型、装饰器模式在 IO 中的运用、字节流与字符流的区别。

| 天数 | 主题 | 核心 API | 检验方式 |
|------|------|----------|----------|
| 第1天 | 字节流基础 | `InputStream`, `OutputStream`, `FileInputStream`, `FileOutputStream` | 实现文件字节级复制 |
| 第2天 | 字符流与编码 | `Reader`, `Writer`, `InputStreamReader`, `OutputStreamWriter`, `FileReader`, `FileWriter` | 处理含中文的文本文件读写 |
| 第3天 | 缓冲与装饰器 | `BufferedInputStream`, `BufferedOutputStream`, `BufferedReader`, `BufferedWriter` | 对比有无缓冲的性能差异 |
| 第4天 | 数据流与对象流 | `DataInputStream`, `DataOutputStream`, `ObjectInputStream`, `ObjectOutputStream` | 读写基本类型和序列化对象 |
| 第5天 | 随机访问与文件操作 | `RandomAccessFile`, `File` | 实现断点续传写入 |
| 第6天 | 标准IO与打印流 | `System.in`, `System.out`, `System.err`, `PrintStream`, `PrintWriter` | 实现简单的命令行交互程序 |
| 第7天 | 综合练习 | 综合运用 | 实现一个带缓冲的配置文件解析器 |

#### 核心知识点检查清单

- [✅] `read()` 返回 -1 的含义:
>表示读取到文件末尾
- [✅] `read(byte[])` 的返回值含义:
>表示实际读取到的字节数, -1 表示读取到文件末尾
- [✅] 字节流 vs 字符流的本质区别（编码）: 
>字节流直接读写字节，可自定义字节的编码方式，可以读写任意文件（不正确读取转换可能出现乱码），字符流是提供了字符编码和解码的机制，按照字符编码保存，读取时自动解码，可以按行读取（读取时通过换行符进行换行读取）只能处理文本文件
- [✅] `flush()` 三层缓冲模型：Java缓冲区 → OS Page Cache → 磁盘；`FileOutputStream.flush()` 是 no-op（源码验证）
> 流的写入比如BufferedOutputStream、BufferedWriter等，需要手动调用flush()方法，才能将缓冲区中的数据写入磁盘，3次. 这些缓冲池的先是写入到Java缓冲中通过byte[]或char[]保存，调用flush时，会将数组中的数据写入os的缓存页，最后交由OS自主（一般十几秒）刷新到磁盘。
- [✅] 装饰器模式的类图，如何在 IO 中体现
> 装饰器模式在 IO 中的体现，比如BufferedInputStream、BufferedOutputStream等，都是装饰器模式的实现。BufferedInputStream继承了FilterInputStream有个InputStream in字段，用于存储实际的输入流。同时BufferedInputStream有一个byte[] buf字段，用于存储缓冲区中的数据。
- [✅] `ObjectInputStream` 的安全问题（反序列化漏洞）
- [ ] `transient` 关键字的作用
- [ ] `serialVersionUID` 的作用
- [ ] `RandomAccessFile` 的 mode 参数
- [ ] try-with-resources 的实现原理（`AutoCloseable`）

---

### 阶段 2：NIO 核心（1.5 周）

**目标**：掌握 Buffer、Channel、Selector 三大核心组件，理解面向缓冲区的非阻塞 IO 模型。

| 天数 | 主题 | 核心 API | 检验方式 |
|------|------|----------|----------|
| 第8-9天 | Buffer 详解 | `ByteBuffer`, `allocate()`, `allocateDirect()`, `put`, `get`, `flip`, `clear`, `compact`, `mark`, `reset` | 手动操作 Buffer 的三个指针验证理解 |
| 第10天 | FileChannel | `FileChannel`, `read`, `write`, `transferTo`, `transferFrom` | 对比传统 IO 与 NIO 的文件复制性能 |
| 第11天 | 内存映射 | `MappedByteBuffer`, `FileChannel.map()` | 实现大文件（>1GB）的高效读取 |
| 第12天 | Socket 网络通道 | `SocketChannel`, `ServerSocketChannel` | 实现阻塞式 NIO Client/Server |
| 第13-14天 | Selector 多路复用 | `Selector`, `SelectionKey`, `select()`, `OP_READ`, `OP_WRITE`, `OP_CONNECT`, `OP_ACCEPT` | 实现单线程处理多连接的非阻塞服务器 |
| 第15天 | Scatter/Gather | `scatteringRead`, `gatheringWrite` | 实现自定义协议的消息拼接 |

#### 核心知识点检查清单

- [ ] Buffer 的三个关键指针：`position`、`limit`、`capacity`
- [ ] `flip()` 做了什么？为什么读写切换需要它？
- [ ] `clear()` vs `compact()` 的区别
- [ ] 直接缓冲区（Direct Buffer）vs 堆缓冲区（Heap Buffer）的内存模型差异
- [ ] `allocateDirect()` 的优缺点
- [ ] Channel 和 Stream 的根本区别（双向 vs 单向）
- [ ] `FileChannel` 的 `position()` 方法含义
- [ ] `Selector` 的 `select()` 返回值含义
- [ ] `SelectionKey` 的 `interestOps` vs `readyOps`
- [ ] 为什么 `select()` 返回后要移除已处理的 key（`iterator.remove()`）
- [ ] 零拷贝（`transferTo`/`transferFrom`）的底层原理
- [ ] `MappedByteBuffer` 的 `force()` 方法作用

---

### 阶段 3：NIO.2 文件系统 API（1 周）

**目标**：掌握 Java 7+ 引入的现代化文件系统 API。

| 天数 | 主题 | 核心 API | 检验方式 |
|------|------|----------|----------|
| 第16天 | Path & Files 基础 | `Path`, `Paths`, `Files.copy`, `Files.move`, `Files.delete`, `Files.createDirectory` | 实现递归目录复制 |
| 第17天 | 文件属性 | `Files.readAttributes`, `BasicFileAttributes`, `DosFileAttributes`, `PosixFileAttributes` | 实现 `ls -l` 风格的命令 |
| 第18天 | 文件遍历 | `Files.walk`, `Files.walkFileTree`, `FileVisitor`, `SimpleFileVisitor` | 实现 `find` 命令 |
| 第19天 | 文件监听 | `WatchService`, `WatchKey`, `StandardWatchEventKinds` | 监控目录变更 |
| 第20天 | 异步文件通道 | `AsynchronousFileChannel`, `CompletionHandler`, `Future` | 异步读写大文件 |
| 第21天 | 综合练习 | 综合运用 | 实现文件同步工具 |

#### 核心知识点检查清单

- [ ] `Path.resolve()` vs `Path.resolveSibling()`
- [ ] `Path.relativize()` 的用法
- [ ] `Files.walk()` 的深度优先遍历机制
- [ ] `Files.walk()` 的 `maxDepth` 参数
- [ ] `FileVisitor` 的四个回调方法：`preVisitDirectory`, `visitFile`, `visitFileFailed`, `postVisitDirectory`
- [ ] `FileVisitResult` 的四种返回值
- [ ] `WatchService` 的事件类型：`ENTRY_CREATE`, `ENTRY_DELETE`, `ENTRY_MODIFY`
- [ ] `WatchKey.reset()` 的必要性
- [ ] `AsynchronousFileChannel` vs 传统的 `FileChannel` + 线程池
- [ ] `CompletionHandler` vs `Future` 两种异步模型的选择

---

### 阶段 4：高级主题与网络编程（1 周）

**目标**：深入理解性能优化和网络编程模型。

| 天数 | 主题 | 核心内容 | 检验方式 |
|------|------|----------|----------|
| 第22天 | 零拷贝深入 | `transferTo`/`transferFrom` 底层 sendfile 系统调用 | 性能压测对比 |
| 第23天 | Reactor 模式 | 单线程 Reactor / 多线程 Reactor / 主从 Reactor | 手写多线程 Reactor |
| 第24天 | Netty 入门 | EventLoop, Channel, Pipeline, Handler | 用 Netty 重写聊天室 |
| 第25天 | AIO 深入 | `AsynchronousSocketChannel`, 回调 vs Future | 实现 AIO 版 Echo Server |
| 第26天 | 序列化方案对比 | JDK Serialization, Protobuf, Kryo, JSON | 性能对比分析 |
| 第27天 | IO 性能调优 | 缓冲区大小策略、批量读写、系统调用开销 | 压测报告 |
| 第28天 | 总复习与查漏补缺 | 全体系串联 | 绘制完整知识脑图 |

#### 核心知识点检查清单

- [ ] `sendfile` 系统调用的原理（数据不经过用户空间）
- [ ] Reactor 模式中 Dispatcher 的角色
- [ ] Netty 中 `bossGroup` 和 `workerGroup` 的职责
- [ ] AIO 中回调在哪个线程执行
- [ ] Protobuf 为什么比 JDK 序列化快
- [ ] IO 操作时缓冲区多大最合理（4KB / 8KB / 64KB？为什么？）

---

## 三、实践项目

### 项目 1：高性能文件复制工具

**难度**：⭐⭐ | **阶段**：1 → 2

```
要求：
1. 支持命令行参数：源文件、目标文件、缓冲区大小
2. 实现三种方式并对比性能：
   - 传统 BufferedInputStream/BufferedOutputStream
   - FileChannel + ByteBuffer
   - FileChannel.transferTo (零拷贝)
3. 输出复制耗时（毫秒）、吞吐量（MB/s）
4. 支持目录递归复制
5. 支持文件覆盖/跳过策略

验收标准：
- 正确处理 >2GB 文件（考量 int 范围限制）
- 正确处理权限不足、磁盘满等异常
- 输出形式化的性能对比表格
```

### 项目 2：多客户端聊天室

**难度**：⭐⭐⭐ | **阶段**：2 → 4

```
要求：
1. 基于 NIO Selector 实现服务端，单线程管理所有连接
2. 支持功能：
   - 用户上线/下线通知
   - 私聊：/msg <user> <content>
   - 广播消息（默认）
   - 在线用户列表：/users
   - 昵称设置：/nick <name>
3. 客户端基于命令行，支持同时收发
4. 支持 ≥ 100 个并发客户端

升级版：
- 用 Netty 重写，对比代码量和性能
- 加入心跳检测和断线重连
- 消息持久化（写入 SQLite）
```

### 项目 3：简易 HTTP 服务器

**难度**：⭐⭐⭐ | **阶段**：2 → 4

```
要求：
1. 基于 NIO 实现，支持 HTTP/1.1
2. 支持的功能：
   - 静态文件服务（HTML/CSS/JS/图片）
   - 正确的 Content-Type 识别
   - 支持部分内容 (Range 请求)
   - Keep-Alive 连接复用
3. 线程模型：主从 Reactor 模式
4. 性能目标：单机 QPS ≥ 5000（简单页面）

升级版：
- 实现 Servlet 容器的雏形
- 支持文件上传（multipart/form-data）
- 实现简单的反向代理
```

### 项目 4：大文件日志解析器

**难度**：⭐⭐⭐⭐ | **阶段**：2 → 4

```
要求：
1. 读取 >10GB 的日志文件，内存占用 < 100MB
2. 使用 MappedByteBuffer 分段映射
3. 支持多行日志聚合（如 Java 异常栈）
4. 支持正则匹配和过滤
5. 输出匹配结果的统计报告（按时间分布、按级别分布）
6. 支持增量解析（文件持续增长）

验收标准：
- 10GB 文件解析内存占用实测 < 100MB
- 正确聚合异常栈
- 增量模式下正确处理半行问题
```

### 项目 5：文件系统监控与同步工具

**难度**：⭐⭐⭐ | **阶段**：3

```
要求：
1. 基于 WatchService 监控指定目录
2. 实时输出变更事件（创建、修改、删除）
3. 支持筛选规则（文件后缀、大小、名称模式）
4. 支持将变更文件自动同步到备份目录
5. 正确处理目录重命名等复杂事件

升级版：
- 支持远程同步（通过 SocketChannel 发送变更）
- 防抖处理（短时间大量变更合并）
```

### 项目 6：网络端口扫描器

**难度**：⭐⭐ | **阶段**：2

```
要求：
1. 扫描指定 IP 的端口范围（如 1-65535）
2. 使用非阻塞 SocketChannel + Selector
3. 支持并发控制（最多同时 N 个连接尝试）
4. 输出开放端口列表及推测的服务类型
5. 支持 TCP Connect 和 SYN 半连接两种模式（需要原始套接字权限）

验收标准：
- 非阻塞模式下扫描 1000 个端口 < 10 秒
- 正确识别 HTTP(80), SSH(22), MySQL(3306) 等常用端口
```

---

## 四、学习成果测验

### 基础测验（阶段 1 完成）

**1. 请写出以下代码的输出结果：**

```java
void main() {
    byte[] data = "你好World".getBytes(StandardCharsets.UTF_8);
    System.out.println(data.length); // ?
    System.out.println("你好World".length()); // ?
}
```

**2. 以下代码有什么问题？**

```java
void main() throws IOException {
    InputStream in = new FileInputStream int b;
    int b;
    while ((b = in.read()) != -1) {
        System.out.print(b + " ");
    }
}
```

**3. 下面代码存在什么问题？"Hello"一定能写入磁盘吗？**

```java
// 方法 A
void methodA() throws IOException {
    FileOutputStream fos = new FileOutputStream("test.txt");
    fos.write("Hello".getBytes());
    fos.flush();  // 这行有用吗？
    // 没有调用 fos.close()
}

// 方法 B
void methodB() throws IOException {
    BufferedOutputStream bos = new BufferedOutputStream(
        new FileOutputStream("test.txt"));
    bos.write("Hello".getBytes());
    bos.flush();
    // 没有调用 bos.close()
}

// 方法 C — 如何保证数据真正落盘？
void methodC() throws IOException {
    try (FileOutputStream fos = new FileOutputStream("test.txt")) {
        fos.write("Hello".getBytes());
        // 如何保证数据真正写入磁盘？
    }
}
```

**4. 实现一个方法，安全地复制 InputStream 到 OutputStream（用 try-with-resources）：**

```java
public static long copy(InputStream in, OutputStream out) throws IOException {
    // 请实现
}
```

---

### NIO 核心测验（阶段 2 完成）

**5. 分析以下 Buffer 操作后的状态：**

```java
void main() {
    ByteBuffer buf = ByteBuffer.allocate(10);
    buf.put((byte) 1);
    buf.put((byte) 2);
    buf.put((byte) 3);
    buf.flip();     // position=? limit=? capacity=?
    buf.get();      // position=? 返回值=?
    buf.compact();  // position=? limit=? capacity=?
}
```

**6. 以下 NIO Server 有什么 bug？**

```java
void main() throws IOException {
    Selector selector = Selector.open();
    ServerSocketChannel ssc = ServerSocketChannel.open();
    ssc.bind(new InetSocketAddress(8080));
    ssc.configureBlocking(false);
    ssc.register(selector, SelectionKey.OP_ACCEPT);

    while (true) {
        selector.select();
        Set<SelectionKey> keys = selector.selectedKeys();
        for (SelectionKey key : keys) {
            if (key.isAcceptable()) {
                SocketChannel sc = ssc.accept();
                sc.configureBlocking(false);
                sc.register(selector, SelectionKey.OP_READ);
            }
            if (key.isReadable()) {
                SocketChannel sc = (SocketChannel) key.channel();
                ByteBuffer buf = ByteBuffer.allocate(1024);
                int n = sc.read(buf);
                if (n == -1) {
                    sc.close();
                }
            }
        }
    }
}
```

**7. 解释 `transferTo` 为什么比 `read + write` 快？它能用在 SocketChannel 到 SocketChannel 之间吗？**

**8. 设计一个环形缓冲区，支持一个生产者线程和一个消费者线程的无锁操作（使用 `ByteBuffer`）。**

---

### NIO.2 测验（阶段 3 完成）

**9. 实现 `find` 命令 —— 递归搜索目录下所有 `.java` 文件，返回路径列表：**

```java
public static List<Path> findJavaFiles(Path startDir) throws IOException {
    // 请实现（使用 Files.walkFileTree）
}
```

**10. 以下 `WatchService` 代码有什么问题？**

```java
void main() {

    WatchService watcher = FileSystems.getDefault().newWatchService();
    Path dir = Paths.get("/tmp");
    dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);

    while (true) {
        WatchKey key = watcher.take();
        for (WatchEvent<?> event : key.pollEvents()) {
            System.out.println(event.kind() + ": " + event.context());
        }
    }
}
```

---

### 综合测验（阶段 4 完成）

**11. 设计题：实现一个高性能消息中间件 Broker 的 IO 层**

```
要求：
1. 支持 TCP 长连接
2. 支持 ≥ 10000 并发连接
3. 消息格式：4字节长度 + payload
4. 支持生产者推送和消费者拉取
5. 描述你的：
   - 线程模型（主从 Reactor / 其他）
   - 缓冲区策略
   - 内存管理（直接缓冲区 vs 堆缓冲区）
   - 流量控制策略
```

**12. 性能题：给定一个 8GB 的 CSV 文件，需要计算某列的平均值**

```
要求：
- 内存占用 < 200MB
- 尽量利用 IO 特性提升性能
- 写出核心代码并解释设计决策
```

**13. 对比题：从以下维度对比 Netty 和原始 NIO**

| 维度 | 原始 NIO | Netty |
|------|----------|-------|
| 线程模型 | | |
| 内存管理 | | |
| 粘包/拆包处理 | | |
| 异常处理 | | |
| 代码行数估算（聊天室） | | |
| 学习曲线 | | |

---

### 测验参考答案

**Q3 (flush 三层模型) 答案：**

1. **方法 A**：`FileOutputStream.flush()` 是 **no-op**（继承自 `OutputStream` 的空实现）。`FileOutputStream` 没有 Java 层缓冲区，每个 `write()` 直接调用 native `write` syscall，数据已进入 OS Page Cache。但 `fos` 没有关闭，文件描述符泄漏。而且数据在 OS Page Cache 中，**没有落盘保证**。

2. **方法 B**：`BufferedOutputStream.flush()` 会将内部 JVM 缓冲区（默认 8192 字节）写入底层 `FileOutputStream`，但 "Hello" 只有 5 字节，可能仍在缓冲区中。这里 `flush()` 确实有作用——推入了 OS Page Cache。`bos` 未关闭，文件描述符泄漏。

3. **方法 C**：`try-with-resources` 保证 `close()`，但 `close()` 只释放文件描述符，不保证落盘。要保证落盘必须用：
```java
void main() throws IOException {
    try (FileOutputStream fos = new FileOutputStream("test.txt")) {
        fos.write("Hello".getBytes());
        fos.getFD().sync();  // fsync() — 阻塞直到数据写入磁盘
    }
}
```

**Q6 (NIO Server bug) 答案：**
1. 没有 `iterator.remove()` — 下次 `select()` 会认为这些 key 仍然就绪 → 空轮询/无限循环
2. 读事件处理中如果 `read()` 返回 0（非阻塞模式下正常），不做任何处理，但 key 也未移除
3. OP_READ 持续就绪：读完后 key 的 interestOps 没有取消或修改 → 会一直触发（虽然 remove 后问题不大）
4. 没有处理 `IOException`（客户端异常断开）

---

## 五、常见面试题与解答思路

### 基础题

#### Q1：字节流和字符流的区别是什么？什么时候用哪个？

**回答要点**：
- 字节流（InputStream/OutputStream）：操作原始字节，适合所有数据类型（二进制文件、图片、视频）
- 字符流（Reader/Writer）：自动处理字符编码，适合文本数据
- 转换桥：InputStreamReader / OutputStreamWriter
- 陷阱：字符流会在编码转换时丢失/损坏数据，不适合二进制

#### Q2：`InputStream.read(byte[])` 返回值的含义是什么？

**回答要点**：
- 返回实际读取的字节数
- 返回 0 表示缓冲区长度为 0
- 返回 -1 表示到达流末尾
- 返回值可能 < 缓冲区长度（剩余数据不足）
- 循环读取时必须用返回值作为实际数据长度

#### Q3：`BufferedInputStream` 的工作原理是什么？缓冲区大小的选择有什么讲究？

**回答要点**：
- 内部维护 `byte[] buf`，默认 8192 字节（8KB）
- 每次 `read` 先尝试从缓冲区取，没了就一次性填充满
- 减少系统调用次数（关键的优化点）
- 缓冲区大小选择：太小 = 系统调用多；太大 = 内存浪费 + 可能触发 GC
- 一般磁盘 IO 推荐 4KB-64KB，网络 IO 推荐 256B-8KB
- Page Cache 对齐：操作系统页大小通常是 4KB

#### Q4：`flush()` 什么情况下是必需的？`FileOutputStream.flush()` 真的刷盘吗？

**回答要点**：

**1. FileOutputStream 根本没有 override `flush()`！**

```java
// java.io.OutputStream — flush() 是空方法！
public void flush() throws IOException {
    // 什么都不做
}

// java.io.FileOutputStream — 没有 override flush()！
// 所以 FileOutputStream.flush() 是一个 no-op
```

验证方法：
```bash
javap -c -p java.io.FileOutputStream | grep flush
# 输出为空 → FileOutputStream 不存在 flush() 字节码
```

**2. 为什么？三层缓冲模型决定了"刷盘"的真相：**

```
用户代码 write()
    │
    ▼
┌─────────────────────────────────┐
│ Layer 1: Java 应用层缓冲区        │  ← BufferedOutputStream / BufferedWriter
│ (JVM 堆内存, 默认 8192 字节)      │     flush() 才会写入下一层
└─────────────────────────────────┘
    │ write 系统调用
    ▼
┌─────────────────────────────────┐
│ Layer 2: OS 内核 Page Cache       │  ← 所有 write() 数据先到这里
│ (内核态内存, 由 OS 管理)           │     fsync/fdatasync 才会刷到磁盘
└─────────────────────────────────┘
    │ 磁盘控制器
    ▼
┌─────────────────────────────────┐
│ Layer 3: 磁盘控制器缓存            │  ← 硬件级缓存
│ (硬件, 有掉电保护或没有)           │     取决于磁盘是否开启写缓存
└─────────────────────────────────┘
    │
    ▼
  磁盘盘片
```

- `FileOutputStream` **没有 Layer 1 缓冲区**：每个 `write()` 都直接调用 native 方法写入 OS
- 所以 `FileOutputStream.flush()` 是空操作（no-op）— 没东西可 flush
- 但这**不代表数据已落盘** — 数据只是在 **Layer 2 (Page Cache)** 中
- OS 会在合适的时机（如 dirty_ratio 阈值、定期 pdflush）将脏页写回磁盘

**3. 各 API 真正的作用对比：**

| 方法 | 作用层级 | 实际效果 |
|------|---------|---------|
| `FileOutputStream.flush()` | 无 | **no-op**，什么都没做 |
| `BufferedOutputStream.flush()` | Layer 1 → OS | 将 JVM 缓冲区推入 OS (write syscall) |
| `FileDescriptor.sync()` | Layer 2 → 磁盘 | 调用 `fsync()` — 阻塞直到数据写入磁盘 |
| `FileChannel.force(false)` | Layer 2 → 磁盘 | 调用 `fdatasync()` — 仅刷新数据，不刷新元数据 |
| `FileChannel.force(true)` | Layer 2 → 磁盘 | 调用 `fsync()` — 刷新数据 + 元数据（如 mtime） |

**4. 面试完整回答模板：**

> "Java IO 的 flush 有两层含义。第一层是 Java 层的缓冲区刷新：`BufferedOutputStream.flush()` 将内部缓冲区数据通过 write 系统调用写入 OS 内核的 Page Cache。`FileOutputStream` 因为没有 Java 层缓冲区，所以它根本没有 override `flush()`，继承自 `OutputStream` 的空实现，是一个 no-op。
>
> 第二层是 OS 层的刷盘：即使数据到了 Page Cache，也不一定在磁盘上。要保证持久化，必须调用 `FileDescriptor.sync()` 或 `FileChannel.force(true)`，它们底层分别调用 POSIX 的 `fsync()` 和 `fdatasync()` 系统调用。
>
> `close()` 只保证 Java 层数据写出 + 释放文件描述符，**不保证数据落盘**。MySQL/Redis 等对持久化有要求的系统，会在关键路径上显式调用 `fsync`。"

**5. 典型的"文件为空" bug 只在有 Java 层缓冲时才出现：**

```java
// bug: BufferedOutputStream 不 flush/close → 数据丢失
BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream("a.txt"));
bos.write("hello".getBytes());
// 没有 flush/close → 数据残留在 BufferedOutputStream 的 8KB 内部缓冲区中

// 不会 bug: FileOutputStream 无内部缓冲区
FileOutputStream fos = new FileOutputStream("b.txt");
fos.write("hello".getBytes());
// 数据已通过 write 系统调用进入 OS Page Cache（但未必在磁盘上！）
```

#### Q5：Java 序列化机制的工作方式，`serialVersionUID` 的作用？

**回答要点**：
- `serialVersionUID` 用于版本控制：反序列化时验证类的版本一致性
- 不显式声明时，JVM 根据类结构自动生成（字段变更即变）
- 显式声明 `private static final long serialVersionUID = 1L` 可以保持向后兼容
- `transient` 跳过序列化
- 安全问题：`ObjectInputStream` 的反序列化可以触发任意代码执行（反序列化漏洞）
- 替代方案：JSON、Protobuf、Kryo 等

---

### NIO 核心题

#### Q6：Buffer 的 `flip()`、`clear()`、`compact()` 有什么区别？

**回答要点**：

| 方法 | position | limit | 用途 |
|------|----------|-------|------|
| `flip()` | 0 | position | 写模式→读模式 |
| `clear()` | 0 | capacity | 准备重新从头写（丢弃未读数据） |
| `compact()` | 剩余未读数据的数量 | capacity | 保留未读数据，准备继续写 |

面试加分项：画图展示三个指针的变化过程。

#### Q7：直接缓冲区（Direct Buffer）和堆缓冲区（Heap Buffer）有什么区别？

**回答要点**：
- 堆缓冲区：在 JVM 堆上分配，受 GC 管理；GC 回收可能导致数据复制
- 直接缓冲区：在堆外内存分配，不受 GC 管理，通过 `Cleaner` 机制释放
- 直接缓冲区优势：减少一次数据拷贝（JVM 堆 ↔ 本地堆），适合 IO 操作
- 直接缓冲区劣势：分配和释放成本高，不适合频繁创建销毁
- 适用场景：长期存在的 IO Buffer 用 Direct；短生命周期用 Heap
- `-XX:MaxDirectMemorySize` 控制上限

#### Q8：Channel 和 Stream 的根本区别？

**回答要点**：
- Channel 是双向的（可读可写），Stream 是单向的
- Channel 可以异步读写，Stream 通常是阻塞的
- Channel 总是和 Buffer 配合使用
- Channel 支持 scatter/gather
- Channel 支持非阻塞模式
- Channel 有 `position()` 方法可以定位

#### Q9：Selector 的工作原理？`select()` 方法的阻塞和非阻塞模式？

**回答要点**：
- Selector 是 NIO 多路复用的核心，底层基于操作系统的 IO 多路复用机制
- Linux：epoll / Windows：IOCP / macOS：kqueue
- `select()`：阻塞，直到至少一个通道就绪
- `select(long timeout)`：阻塞最多 timeout 毫秒
- `selectNow()`：非阻塞，立即返回
- 工作流程：注册 Channel → select 轮询 → 处理就绪 key → 清空 selectedKeys → 循环
- 关键：必须从 selectedKeys 中移除已处理的 key（`iterator.remove()`）
- `OP_READ` 持续就绪的问题（需要 cancel interestOps）

#### Q10：解释零拷贝（Zero Copy）的原理？

**回答要点**：
- 传统方式：磁盘 → 内核缓冲区 → 用户缓冲区 → Socket 缓冲区 → 网卡（4 次拷贝 + 4 次上下文切换）
- `transferTo` 零拷贝：磁盘 → 内核缓冲区 → Socket 缓冲区 → 网卡（2 次拷贝，其中 1 次 DMA gather）
- 数据不经过用户空间，CPU 不参与数据拷贝
- Linux 底层是 `sendfile()` 系统调用
- 限制：只能从 FileChannel 传输，不能修改数据
- Kafka 大量使用零拷贝实现高性能

#### Q11：Scatter 和 Gather 是什么？有什么应用？

**回答要点**：
- Scatter（分散读）：读到一个 Buffer 数组中 → `scatteringRead(ByteBuffer[])`
- Gather（聚集写）：从多个 Buffer 依次写出 → `gatheringWrite(ByteBuffer[])`
- 应用：自定义协议（固定长度 header + 可变 body）
- 操作系统原生支持 `readv()` / `writev()` 系统调用
- 原子性保证 （一次系统调用完成多段操作）

---

### NIO.2 题

#### Q12：`Path` 和 `File` 的区别？为什么推荐使用 `Path`？

**回答要点**：
- `Path` 是 NIO.2 (Java 7+) 引入的接口，`File` 是传统 IO
- `Path` 支持更丰富的路径操作（`resolve`, `relativize`, `normalize`……）
- `Path` 与 `Files` 工具类配合，功能远超 `File`
- `Path` 可以表示不存在的路径，不会抛异常
- `File.toPath()` 可以从传统 API 迁移到 NIO.2
- `File` 的主要问题：错误处理差（boolean 返回值），不支持符号链接

#### Q13：`WatchService` 的实现原理和局限性？

**回答要点**：
- 底层依赖 OS 的文件系统事件通知机制
- Linux：inotify / macOS：FSEvents / Windows：ReadDirectoryChangesW
- 局限性：
  - 不一定支持递归监听（需要自行实现）
  - 事件可能丢失（缓冲区溢出）
  - `ENTRY_MODIFY` 可能触发多次
  - 不同 OS 行为有差异
  - 网络文件系统可能不支持
- `WatchKey.reset()` 必须调用，否则收不到后续事件

---

### 高级与设计题

#### Q14：谈谈 Reactor 线程模型？单 Reactor、多 Reactor、主从 Reactor 的区别？

**回答要点**：

**单线程 Reactor**：
- 一个线程处理 accept、read、write、业务逻辑
- 优点：简单、无线程安全
- 缺点：单核性能瓶颈、一个 handler 阻塞会影响全局
- 代表：Redis

**多线程 Reactor（主从 Reactor 的前身）**：
- 一个线程 accept + 线程池处理 read/write/业务
- 读/写和处理在不同线程，需要线程安全

**主从 Reactor**：
```
MainReactor (accept) → SubReactor 1 (read/write) → Worker Thread Pool (业务)
                      → SubReactor 2 (read/write) → Worker Thread Pool (业务)
                      → SubReactor N (read/write) → Worker Thread Pool (业务)
```
- bossGroup 负责 accept
- workerGroup 负责 read/write
- 业务处理交给独立的线程池
- Netty 的标准模型

#### Q15：Netty 中的 `ByteBuf` 相比 `ByteBuffer` 有哪些改进？

**回答要点**：
- 读写指针分离（`readerIndex` / `writerIndex`），不需 flip
- 自动扩容
- 池化缓冲区（PooledByteBufAllocator）减少 GC
- 引用计数（ReferenceCounted）管理生命周期
- 支持复合缓冲区 (CompositeByteBuf)，零拷贝合并
- 内置多种 ByteBuf 类型（Heap、Direct、Composite、Pooled）
- 更灵活的切片/复制语义（slice, duplicate, copy）

#### Q16：如何解决 TCP 粘包/拆包问题？

**回答要点**：
- 粘包/拆包原因：TCP 是流式协议，没有消息边界
- 四种经典方案：
  1. **定长消息**：每条消息固定 N 字节，不足补齐
  2. **分隔符**：每个消息以特定字符结尾（如 `\n`）
  3. **长度字段**：消息头包含长度，先读固定头再读变长体（最常用）
  4. **自描述协议**：如 HTTP 的 Content-Length
- Netty 内置解码器：`FixedLengthFrameDecoder`, `DelimiterBasedFrameDecoder`, `LengthFieldBasedFrameDecoder`
- 手写 NIO 时需要特别注意：`ByteBuffer` 里可能有多条或部分消息

#### Q17：如果让你设计一个高性能文件下载服务，你会怎么选择 IO 模型？

**回答要点**：
- 使用零拷贝 `transferTo` 直接发送文件到 Socket
- 对于大文件，使用 `sendfile` 零拷贝 + HTTP Range 分片并行传输
- 线程模型：主从 Reactor + 定长任务队列
- 内存：使用直接缓冲区和池化管理
- 磁盘 IO 优化：预读（fadvise）、使用 `MappedByteBuffer`（小文件）
- 流量控制：利用 Netty 的高低水位机制或 TCP 反压

#### Q18：对比 NIO 的 `select()` / `poll()` / `epoll()` 底层实现差异？

**回答要点**：

| 维度 | select | poll | epoll |
|------|--------|------|-------|
| 数据结构 | fd_set 位图（1024限制） | pollfd 数组（无限制） | 红黑树 + 就绪链表 |
| fd 数量上限 | FD_SETSIZE (1024) | 无 | 无（受系统限制） |
| 遍历方式 | O(n) 全部遍历 | O(n) 全部遍历 | O(1) 只遍历就绪 fd |
| fd 拷贝 | 每次调用全量拷贝 | 每次调用全量拷贝 | fd 注册一次（epoll_ctl） |
| 触发模式 | 水平触发 | 水平触发 | 水平触发 + 边缘触发 |
| Java 映射 | JDK NIO 自动选择最优实现 | - | Linux 默认 epoll |

重点关注：epoll 的边缘触发（ET）vs 水平触发（LT），ET 模式下必须一次读完否则永远收不到后续事件。

---

### 场景分析题

#### Q19：一个线上服务使用 NIO，发现内存持续增长，最终 OOM。可能的原因有哪些？如何排查？

**排查思路**：
1. 直接缓冲区泄漏：Direct Buffer 超出 `-XX:MaxDirectMemorySize`
2. `SelectionKey.cancel()` 但 Channel 未关闭
3. 消息堆积：消费者处理慢 → `ByteBuffer` 积压
4. `MappedByteBuffer` 未释放（GC 不会立即回收）
5. Heap Dump 分析：`jmap -dump`, MAT 分析大对象

**解决**：
- 池化管理 ByteBuffer
- 及时 cancel key + close channel
- 引入流量控制和背压机制
- 使用 Netty 的引用计数

#### Q20：如何选择最合适的 IO 模型：BIO、NIO、AIO？

| 场景 | 推荐 | 理由 |
|------|------|------|
| 连接数少（<100）、长连接 | BIO | 简单，每连接一线程 |
| 连接数多（>1000）、短连接 | NIO（Selector） | 少线程管理多连接 |
| 连接数极多（>10000） | NIO + Netty | Netty 解决复杂性 |
| 文件读写密集型 | NIO（FileChannel + MappedByteBuffer） | 零拷贝 + 内存映射 |
| Windows 平台高并发 | AIO（IOCP） | Windows IOCP 成熟 |
| Linux 平台高并发 | NIO（epoll） | epoll 性能优秀，AIO 实现不成熟 |

---

## 六、参考资料

### 必读

1. **《Java NIO》** — Ron Hitchens（O'Reilly）— NIO 领域的经典
2. **《Netty 实战》** — Norman Maurer（Netty 核心开发者）
3. **《Java 网络编程》** — Elliotte Rusty Harold
4. **《深入理解 Java 虚拟机》第 5 章** — 周志明（直接内存相关）

### 在线资源

5. [Java IO Tutorial (Oracle)](https://docs.oracle.com/javase/tutorial/essential/io/)
6. [Java NIO Tutorial (Jenkov)](https://jenkov.com/tutorials/java-nio/index.html)
7. [Netty 官方文档](https://netty.io/wiki/user-guide.html)
8. [The Linux Programming Interface — IO Multiplexing](https://man7.org/tlpi/)

### 源码阅读建议

9. OpenJDK `sun.nio.ch` 包 — 理解 Selector 的实现
10. Netty 源码中 `NioEventLoop` 和 `AbstractNioByteChannel`

---

## 附录：技术雷达

```
入门（会用 API）
  │
  ├── 阶段1: 传统 IO 流
  ├── 阶段2: NIO Buffer/Channel
  ├── 阶段3: NIO.2 Files API
  │
中级（理解原理）
  │
  ├── 阶段2: Selector 多路复用
  ├── 阶段4: 零拷贝/内存映射
  ├── 阶段4: Reactor 模式
  │
高级（能设计系统）
  │
  ├── 阶段4: Netty 深入
  ├── OS 内核: epoll/IOCP/kqueue 原理
  ├── 性能调优: GC 与 Direct Memory
  ├── 分布式: 消息中间件 IO 设计
  │
专家（能写出框架）
  │
  ├── 自研 RPC 框架
  ├── 自研消息队列
  └── 对 Netty 贡献代码或深度定制
```

---

*持续更新中。建议将这个文件作为学习 Checklist，每学完一个知识点就勾选对应的复选框。*
