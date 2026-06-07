# JAVA Conclusion

覆盖从JVM到数据结构 三方框架，集合，设计模式，IO，分布式系统，分布式事务，数据库，缓存，非关系型数据库，AI领域，spring生态以及原理等

## JVM 工作原理

JVM 是 Java 虚拟机的缩写，运行 Java 字节码的抽象计算机。核心工作原理：**类加载 → 运行时数据区分配 → 执行引擎解释/JIT编译执行 → 垃圾回收**。

---

### 一、类加载机制

#### 1.1 基本流程

```text
.java → javac → .class（字节码）→ ClassLoader 加载到 JVM
```

类的生命周期：**加载 → 链接（验证→准备→解析）→ 初始化 → 使用 → 卸载**

#### 1.2 类加载器体系（JDK 9+ 模块化）

| 加载器    | JDK 8                                              | JDK 9+                                                                          | 职责                                  |
|--------|----------------------------------------------------|---------------------------------------------------------------------------------|-------------------------------------|
| 启动类加载器 | Bootstrap CL（C++ 实现，Java 中为 null）                  | **BootClassLoader**（`jdk.internal.loader.ClassLoaders$BootClassLoader`）         | 加载 `java.base` 等基础模块                |
| 平台类加载器 | Extension CL（`sun.misc.Launcher$ExtClassLoader`）   | **PlatformClassLoader**（`jdk.internal.loader.ClassLoaders$PlatformClassLoader`） | 加载平台模块（`java.sql`、`java.desktop` 等） |
| 应用类加载器 | Application CL（`sun.misc.Launcher$AppClassLoader`） | **AppClassLoader**（`jdk.internal.loader.ClassLoaders$AppClassLoader`）           | 加载应用模块和 classpath                   |

**JDK 9+ 核心变化：**

- `rt.jar` 被拆分为约 90 个模块，每个模块有 `module-info.java`
- Extension CL → PlatformClassLoader：`lib/ext/` 机制废弃，改为加载平台模块
- 委派模型从**严格双亲委派**演进为**基于模块解析图的委派**：ClassLoader 根据模块依赖关系直接定位拥有该模块的 ClassLoader，而非逐层向上传递

#### 1.3 双亲委派

**核心思想**：自底向上检查是否已加载，自顶向下尝试加载。

**为什么需要？** → 避免核心类被篡改，保证类型安全。例如自定义 `java.lang.String` 不会被加载，因为 Bootstrap/BootClassLoader 已经加载了核心版本。

**打破双亲委派的场景：**

- **SPI 机制**（JDBC/JNDI）：核心类需要加载第三方实现，用线程上下文类加载器（`Thread.getContextClassLoader()`）
- **Tomcat**：每个 WebApp 独立 ClassLoader，优先加载自己目录下的类（先自己加载，再委派父类），实现应用隔离
- **OSGi**：网状类加载，模块间可按需委派

#### 1.4 模块化（JPMS）的强封装

```java
module com.myapp {
    exports com.myapp.api;       // 只暴露 api 包
    requires java.sql;           // 依赖 JDBC 模块
    // com.myapp.internal 没有 exports → 外部不可见
}
```

即使类是 `public`，包没有 `exports` 就对外不可见。反射也需要 `--add-opens` 才能访问未导出的包。

---

### 二、运行时数据区

| 区域 | 线程共享？ | 存储内容 | 异常 |
| ------ | ----------- | --------- | ------ |
| **堆 Heap** | 共享 | 对象实例、数组 | OOM |
| **方法区** | 共享 | 类信息、常量、静态变量（JDK 8+ 元空间 Metaspace，使用本地内存） | OOM |
| **虚拟机栈** | 私有 | 栈帧（局部变量表、操作数栈、动态链接、返回地址） | StackOverflowError / OOM |
| **本地方法栈** | 私有 | Native 方法调用 | StackOverflowError / OOM |
| **程序计数器 PC** | 私有 | 当前执行的字节码行号 | 唯一不会 OOM 的区域 |

**PermGen → Metaspace 的演进（JDK 8）：**

- PermGen 在堆中，大小固定，容易 OOM（`java.lang.OutOfMemoryError: PermGen space`）
- Metaspace 使用本地内存，默认无上限（受物理内存限制），按需增长
- 字符串常量池从 PermGen 移至堆中

---

### 三、执行引擎

#### 3.1 解释器 + JIT 混合执行

```text
字节码 ──→ 解释器逐条执行（启动快，运行慢）
    │
    └──→ JIT 编译器编译为机器码（启动慢，运行快）
```

#### 3.2 热点探测

方法调用计数器 / 回边计数器超过阈值 → 触发 JIT 编译。

#### 3.3 C1 / C2 编译器

| | C1（Client） | C2（Server） |
| --- | --- | --- |
| 优化程度 | 浅层优化 | 深度优化 |
| 编译速度 | 快 | 慢 |
| 典型优化 | 方法内联、简单逃逸分析 | 逃逸分析、锁消除、标量替换、循环优化 |

**分层编译**（Tiered Compilation，JDK 8 默认开启）：C1 先编译热点，更热的再交 C2 深度优化。

#### 3.4 逃逸分析（JIT 核心优化）

分析对象的作用域是否"逃逸"出方法：

- **未逃逸 → 栈上分配**：对象不分配在堆上，方法结束自动销毁，无需 GC
- **未逃逸 → 标量替换**：对象拆解为基本类型，直接用寄存器/栈存储
- **未逃逸 → 锁消除**：`synchronized` 加锁的对象只有当前线程使用，锁操作被消除

---

### 四、垃圾回收

#### 4.1 什么对象该回收？— 可达性分析

从 **GC Roots** 出发，不可达的对象即回收。

GC Roots 包括：栈帧局部变量、静态引用、JNI 引用、锁持有对象、线程对象、类加载器等。

> 引用计数法有循环引用问题，JVM 不使用。

#### 4.2 四种引用类型

| 引用类型 | 回收时机 | 用途 |
|---------|---------|------|
| 强引用（Strong） | 永不回收（除非不可达） | 普通引用 |
| 软引用（Soft） | 内存不足时回收 | 缓存 |
| 弱引用（Weak） | 下次 GC 时回收 | WeakHashMap、ThreadLocal |
| 虚引用（Phantom） | 随时回收，get() 总返回 null | 跟踪 GC、管理堆外内存 |

#### 4.3 分代模型

```
┌──────────────────────────────────────────────────┐
│                      堆                           │
│  ┌─────────────────────────┬───────────────────┐ │
│  │        新生代 Young       │     老年代 Old     │ │
│  │  ┌──────┬──────┬──────┐ │                   │ │
│  │  │ Eden │ S0   │ S1   │ │                   │ │
│  │  │ 80%  │ 10%  │ 10%  │ │                   │ │
│  │  └──────┴──────┴──────┘ │                   │ │
│  └─────────────────────────┴───────────────────┘ │
└──────────────────────────────────────────────────┘
```

- **Minor GC**：Eden 区满触发，频率高、速度快，回收新生代
- **Major GC / Full GC**：老年代/元空间满触发，STW 时间长，回收整个堆

对象晋升：Eden → Survivor（来回复制，每复制一次年龄+1）→ 年龄达到阈值（默认 15）→ 晋升老年代。大对象直接进入老年代。

#### 4.4 GC 算法与收集器

| 算法 | 特点 | 适用场景 |
|------|------|---------|
| 标记-清除 | 碎片多 | CMS（已移除） |
| 标记-整理 | 无碎片但移动成本高 | Serial Old、Parallel Old |
| 复制算法 | 无碎片但空间折半 | 新生代（Eden + S0/S1） |
| 分代收集 | 新生代复制 + 老年代标记整理 | G1、ZGC |

#### 4.5 收集器演进（重点）

```
Serial → Parallel → CMS → G1 → ZGC

CMS（JDK 14 移除）：
  并发标记-清除，减少 STW，但碎片多 + 并发失败退回 Serial Old

G1（JDK 9 默认）：
  Region 化，可预测停顿时间（-XX:MaxGCPauseMillis）
  兼顾吞吐和延迟，适合大堆（6GB+）

ZGC（JDK 11 实验，JDK 15 生产可用，JDK 21 增强）：
  染色指针 + 读屏障，STW < 1ms
  支持 TB 级堆，适合超大堆 + 低延迟场景
  JDK 21 支持分代 ZGC（-XX:+UseZGC -XX:+ZGenerational）
```

**G1 vs ZGC 选择：**

- 堆 < 32GB，延迟要求 100-200ms → G1
- 堆 > 32GB，或延迟要求 < 10ms → ZGC

#### 4.6 三色标记法

- **白色**：未被访问，回收候选
- **灰色**：已访问但引用未处理完
- **黑色**：已访问且引用处理完，存活

**漏标问题**：灰色对象断开对白色对象的引用，同时黑色对象新增对该白色对象的引用 → 白色被误回收。

**解决方案**：

- **增量更新**（CMS 用）：记录黑色→白色的新增引用，重新标记阶段以新增引用的黑色为根重新扫描
- **原始快照 SATB**（G1 用）：记录灰色→白色被删除的引用，重新标记阶段以删除前的引用关系重新扫描
- **写屏障**：在引用变更时触发回调，记录上述信息

---

### 五、JDK 21 新特性：虚拟线程

#### 5.1 是什么

虚拟线程是 JVM 管理的轻量级线程，M:N 调度（多个虚拟线程映射到少量载体线程/OS 线程）。

#### 5.2 核心机制

**三个关键组件：**

1. **Continuation**：运行时捕获/恢复完整调用栈（不是编译期状态机，所以不需要 async/await）
2. **ForkJoinPool**：作为调度器管理就绪虚拟线程的工作队列，支持 work-stealing
3. **事件驱动唤醒**：epoll/kqueue 通知 I/O 就绪、DelayedQueue 定时唤醒、LockSupport.unpark 通知锁释放

**卸载流程**：虚拟线程执行阻塞 I/O → JVM 将阻塞调用偷换为非阻塞 + epoll 注册 → Continuation.yield() 保存栈帧到堆 → 载体线程去执行其他虚拟线程 → I/O 就绪事件通知 → 虚拟线程重新入队 ForkJoinPool → 载体线程恢复 Continuation 继续执行

**核心价值**：虚拟线程做阻塞 I/O 时，JVM 在底层把阻塞调用转成非阻塞 + 事件驱动，载体线程不闲置。开发者写阻塞式代码，获得非阻塞性能。

#### 5.3 与其他语言协程的对比

|       | Java Virtual Thread | Go Goroutine | Python/Rust Coroutine    |
|-------|---------------------|--------------|--------------------------|
| 调度方式  | 自动协作式（阻塞点自动让出）      | 抢占式          | 显式协作式（必须 await）          |
| 函数染色  | ❌ 无，同步写法            | ❌ 无          | ✅ 有，async/await 感染整个调用链  |
| 需要异步库 | ❌ 现有同步库直接可用         | ❌            | ✅ 需要 aiohttp/tokio 等异步版库 |

#### 5.4 Pinning 问题

`synchronized` 内做阻塞操作会导致虚拟线程"钉"在载体线程上。

**原因**：synchronized 的 monitor 归属绑定载体线程（OS 线程）身份，卸载会破坏锁语义（monitorexit 检查锁持有者是否为当前载体线程，切换后身份不匹配）。

**解决**：用 `ReentrantLock` 替代。ReentrantLock 的锁状态存储在 AQS 中，归属记录的是虚拟线程本身（而非载体线程），且通过 LockSupport.park() 阻塞，这是 JVM 虚拟线程感知的安全点，可以安全卸载。

#### 5.5 适用场景

- ✅ I/O 密集型：网络调用、数据库查询、文件读写、HTTP 请求
- ❌ CPU 密集型：用 ForkJoinPool / ParallelStream
- ❌ Native 方法中的阻塞：JVM 无法拦截

---

### 六、面试拓展问题与回答

#### Q1: 类的初始化顺序？

```text
父类静态变量 → 父类静态代码块 → 子类静态变量 → 子类静态代码块
→ 父类实例变量 → 父类构造方法 → 子类实例变量 → 子类构造方法
```

#### Q2: 什么情况下类不会被卸载？

- JVM 规范没有强制要求类卸载
- Bootstrap/BootClassLoader 加载的类不会被卸载
- 类卸载条件极苛刻：该类所有实例被回收、加载该类的 ClassLoader 被回收、该类对应的 java.lang.Class 对象没有在任何地方被引用

#### Q3: OOM 排查思路？

1. **保留现场**：`-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/dump.hprof`
2. **分析 dump**：MAT / VisualVM / JProfiler 找大对象和 GC Root 引用链
3. **常见原因**：
   - 堆 OOM：内存泄漏（静态集合持有引用、ThreadLocal 未 remove、未关闭资源）
   - Metaspace OOM：动态代理/CGLIB 大量生成类
   - 栈 OOM：递归过深或线程数过多
4. **线上应急**：`jmap -histo:live <pid>` 查看存活对象分布，`jstat -gcutil` 监控 GC 情况

#### Q4: Full GC 频繁怎么排查？

1. `jstat -gcutil <pid> 1000` 观察 GC 频率和各区域使用率
2. 确认 Full GC 触发原因：
   - 老年代满：大对象/内存泄漏 → dump 分析
   - Metaspace 满：动态类生成 → 排查框架使用
   - System.gc()：代码显式调用 → 检查代码或加 `-XX:+DisableExplicitGC`
   - 晋升失败：Survivor 区放不下，对象过早晋升 → 调整新生代比例

#### Q5: G1 和 ZGC 的核心区别？

| | G1 | ZGC |
|---|---|---|
| 堆划分 | 固定大小 Region | 动态 Region（2MB/32MB/×N） |
| 停顿目标 | 100-200ms | < 1ms |
| 屏障类型 | 写屏障（SATB） | 读屏障 + 染色指针 |
| 对象移动 | 需要 STW 才能移动对象 | 并发移动（染色指针 + 读屏障自愈） |
| 适合堆大小 | < 32GB | TB 级别 |
| JDK 21 增强 | — | 分代 ZGC，兼顾年轻代回收效率 |

**染色指针**：ZGC 在 64 位指针中借用几个 bit 标记对象状态，GC 过程不需要修改对象头，只需修改指针中的标记位。读屏障在访问对象时检查指针标记，如果对象已被移动则自动修正引用（自愈），整个过程不需要 STW。

#### Q6: 虚拟线程和线程池怎么选？

| 场景 | 方案 |
|------|------|
| I/O 密集型并发（HTTP/DB/MQ） | `Executors.newVirtualThreadPerTaskExecutor()`，每个任务一个虚拟线程 |
| CPU 密集型计算 | `ForkJoinPool` / `ParallelStream`，线程数 = CPU 核心数 |
| 需要精确控制并发数 | 虚拟线程 + `Semaphore` 限流 |
| 兼容旧代码 | 仍用固定大小线程池 |

**关键认知**：虚拟线程不是替代线程池，而是让 I/O 密集场景不再需要池化。线程池的本质是"复用昂贵资源"，虚拟线程本身足够廉价，无需复用。

```java
// ✅ 虚拟线程 + Semaphore 控制并发度
void main() {
   Semaphore semaphore = new Semaphore(100);
   try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10000; i++) {
         executor.submit(() -> {
            semaphore.acquire();
            try {
               return httpClient.send(request);  // I/O 阻塞 → 自动卸载
            } finally {
               semaphore.release();
            }
         });
      }
   }
}
```

#### Q7: JDK 17+ 面试高频新特性

| 特性                          | JDK 版本 | 说明                                     |
|-----------------------------|--------|----------------------------------------|
| Record 类                    | 16     | 不可变数据载体，自动生成 equals/hashCode/toString  |
| Sealed Classes              | 17     | 限制哪些类可以继承/实现                           |
| Pattern Matching for switch | 21     | `switch(obj) { case String s -> ... }` |
| Virtual Threads             | 21     | 虚拟线程                                   |
| Sequenced Collections       | 21     | 统一有序集合的首尾访问接口                          |

#### Q8: Metaspace OOM 怎么排查？

1. `jstat -gcmetacapacity <pid>` 观察 Metaspace 使用趋势
2. `-XX:MaxMetaspaceSize=256m` 限制上限，触发 dump
3. 常见原因：
   - 动态代理/CGLIB 大量生成类（Spring AOP、Hibernate）
   - JSP 大量编译为 Servlet 类
   - Groovy/Scala 脚本动态编译
4. 解决：排查类加载器泄漏、适当增大 Metaspace

---

### 七、面试回答模板

> JVM 的工作原理可以从四个维度来描述：
>
> **第一，类加载**。Java 源码编译为字节码后，通过类加载器加载到 JVM。JDK 9+ 引入模块化后，类加载器从原来的 Bootstrap/Extension/Application 三层演进为 BootClassLoader/PlatformClassLoader/AppClassLoader，委派模型也从严格双亲委派变为基于模块解析图的委派。模块化还引入了强封装，未 exports 的包即使 public 也对外不可见。
>
> **第二，运行时数据区**。堆存对象实例，方法区（JDK 8+ 为 Metaspace）存类元数据，栈存方法调用的栈帧，PC 存当前执行位置。Metaspace 使用本地内存替代了原来的永久代，解决了 PermGen 容易 OOM 的问题。
>
> **第三，执行引擎**。采用解释器 + JIT 混合模式，热点代码通过 C1/C2 分层编译为机器码。JIT 的核心优化是逃逸分析，能让对象栈上分配、锁消除、标量替换。
>
> **第四，垃圾回收**。通过可达性分析判断对象存活，采用分代收集策略。JDK 9 默认 G1（Region 化、可预测停顿），JDK 21 推荐大堆低延迟场景用 ZGC（染色指针 + 读屏障、STW < 1ms、分代 ZGC）。
>
> 另外 JDK 21 引入的虚拟线程是重要演进，它通过 Continuation 机制在阻塞 I/O 时自动卸载虚拟线程释放载体线程，让同步代码获得非阻塞性能，不需要像 Python/Rust 那样写 async/await。
