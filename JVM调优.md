# 八、JVM 调优方法论

## 8.1 核心原则：不瞎调

```text
调优的敌人不是参数多，而是「我觉得调一下可能会快」
```

**黄金法则：先量测，再动手。没有数据的调优是算命。**

```text
                    问题发现
                       │
                       ▼
              ┌─────────────────┐
              │  1. 建立基线     │  ← jstat / GC 日志 / APM 采集 24h+ 数据
              └────────┬────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  2. 定位瓶颈     │  ← 是 GC？是 CPU？是 I/O？是锁？
              └────────┬────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  3. 形成假设     │  ← "Eden 区太小导致 Minor GC 频繁"
              └────────┬────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  4. 一次改一个   │  ← 只改一个参数，回滚容易，归因清晰
              └────────┬────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  5. 对比验证     │  ← 新基线 vs 旧基线，确认有效才保留
              └─────────────────┘
```

## 8.2 调优之前：先把观测搭好

**没有这些，你就是在黑暗中打拳：**

```bash
# 1. 开启 GC 日志（JDK 17+ 统一格式）
-Xlog:gc*,gc+age=trace:file=/var/log/app/gc.log:time,level,tags:filecount=10,filesize=100M

# 2. 开启 OOM 自动 Dump
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/app/oom.hprof
-XX:+ExitOnOutOfMemoryError  # 可选：OOM 后自杀，让 K8s 重启

# 3. 飞行记录（JFR）- JDK 内置零开销 profiling
-XX:StartFlightRecording:filename=/var/log/app/recording.jfr,dumponexit=true,maxsize=500M
```

**JFR 是 JDK 17+ 调优最重要的工具**——零开销持续采集，事后用 JDK Mission Control 分析，能看到：

- 哪些方法分配了最多内存（对象分配热点）
- 哪些锁竞争最激烈
- I/O 耗时分布
- GC 停顿的精确时间线

```bash
# 运行时按需采集 60 秒 JFR
jcmd <pid> JFR.start duration=60s filename=/tmp/profile.jfr

# 运行时 dump 堆（触发 Full GC，生产慎用）
jcmd <pid> GC.heap_dump /tmp/dump.hprof
```

## 8.3 调优路线图：不是参数列表，是分诊台

拿到一个需要调优的应用，先问自己三个问题，把问题归类再动手：

```text
问题一：吞吐不够？
  → 看 CPU：是不是 GC 线程占了太多 CPU？是不是锁竞争？
  → 看 GC 日志：GC 时间占比 > 5%？
  → 方向：调 GC / 加大堆 / 减少对象分配

问题二：延迟抖动？
  → 看 GC 日志：是不是 Full GC 频繁？STW 时间多长？
  → 看 JFR：哪个线程在等锁？I/O 是否超时？
  → 方向：切 GC 算法 / 锁优化 / 超时调整

问题三：内存不够？
  → 看 GC 日志后 OU（老年代使用量）走势
  → 看 dump：谁占了堆？有没有泄漏？
  → 方向：修泄漏 / 加大堆 / 减少缓存
```

## 8.4 JDK 17+ 参数分层记忆法

不需要记 700 个参数。按**调优场景**分层记忆，每次只关注一层：

**第一层：堆大小（任何应用都要设）**

| 参数 | 建议值 | 依据 |
|------|--------|------|
| `-Xmx` / `-Xms` | 设为相同值 | 避免堆动态扩缩的 STW 开销 |
| `-Xmx` 多大 | 容器内存的 60%~75% | 留内存给 Metaspace + 线程栈 + Native + 堆外 + OS Cache |
| `-XX:MaxMetaspaceSize=256m` | 多数应用够用 | 限制 Metaspace，防止无界增长 |

```bash
# 容器 2G 内存的典型配置
java -Xmx1536m -Xms1536m -XX:MaxMetaspaceSize=256m -jar app.jar
```

**给堆留余量的经验公式：**

```text
堆外内存 = Metaspace(~100-256M) + 线程数×栈大小(~1M/线程) + NIO直接内存 + JVM自身 + OS Cache

容器 2G → 堆给 ~1.5G
容器 4G → 堆给 ~3G
容器 8G → 堆给 ~6G
K8s 容器一定要配 MaxRAMPercentage，否则 JVM 按宿主机内存算堆
-XX:MaxRAMPercentage=75.0   （替代 -Xmx，适配容器环境更好）
```

**第二层：GC 选择（按延迟和堆大小）**

```text
选择逻辑：
  if 堆 < 4GB 且延迟不敏感:
      → 不用动，G1 就行（甚至 Serial 也能用）
  if 堆 4-32GB 且需要控制停顿:
      → G1 + -XX:MaxGCPauseMillis=100
  if 堆 > 16GB 或延迟要求 < 10ms:
      → ZGC
  if Shenandoah (Red Hat 维护):
      → -XX:+UseShenandoahGC
```

```bash
# G1 核心参数（JDK 17+ 默认 GC）
-XX:+UseG1GC                        # 显式指定（其实不用，默认就是）
-XX:MaxGCPauseMillis=100             # 停顿目标，设了 G1 会自适应调整
-XX:G1HeapRegionSize=4m              # 堆 > 8G 可调到 8/16m
-XX:ParallelGCThreads=4              # GC 并行线程数
-XX:ConcGCThreads=2                  # 并发标记线程数（≈ ParallelGCThreads/2）

# ZGC 核心参数（JDK 21 支持分代）
-XX:+UseZGC
-XX:+ZGenerational                   # JDK 21+ 分代 ZGC，吞吐更好
```

**第三层：JIT 和编译（一般不需要动）**

```bash
# 仅特殊情况：
-XX:TieredStopAtLevel=1              # 只用 C1，启动快，适合短生命周期 Serverless
-Xint                                 # 纯解释执行，仅调试用
-XX:ReservedCodeCacheSize=256m        # 代码缓存，IDE/大应用适当加大
```

**第四层：特定场景（记住触发条件，不记参数名）**

| 触发条件 | 参数 | 为什么 |
|----------|------|--------|
| 大量 String 对象 | `-XX:+UseStringDeduplication` | G1 去重相同字符串，节省堆 |
| 用了很多 `ThreadLocal` | 代码里加上 `finally { tl.remove() }` | 比调参数更根本 |
| NIO / Netty 堆外内存泄漏 | 检查 `-XX:MaxDirectMemorySize` | 默认等于 Xmx，可能不够 |
| Lambda 频繁触发去优化 | JFR 看到大量 `InsufficientProfile` | 调整 `-XX:TypeProfileWidth` |

#### 8.5 你的例子：512m 堆没出过问题，还能怎么调？

**如果应用一直没出过问题——先确认是不是真没问题：**

```bash
# 1. 拉一条 24 小时的 GC 基线
jstat -gcutil <pid> 10000 8640 > gc_baseline.log
# 等一天后分析：
#   - FGC 次数是否为 0？
#   - O 老年代使用率是否随时间缓慢爬升？（即使很慢也可能是泄漏）
#   - YGC 频率是否在业务高峰期飙升？

# 2. 采集一次 JFR 看实际对象分配
jcmd <pid> JFR.start duration=300s filename=/tmp/app.jfr
# 用 JDK Mission Control 打开，看：
#   - Memory → Object Allocations：哪些类分配最频繁？
#   - GC → GC Times：STW 是否在可接受范围内？
```

**确认没问题后的优化方向（从业务侧，而非 JVM 侧）：**

```
方向 A：减少内存浪费 → 降配省钱
  当前 -Xmx512m → 观察实际使用峰值
  如果 O 区使用率峰值才 40% → 降到 -Xmx384m
  在 K8s 里省出来的内存可以给其他服务

方向 B：为未来扩容留余地
  确认单次请求创建的临时对象量
  用压测工具加压，观察 GC 行为和吞吐拐点
  JFR 看 Allocation 热点，找到"一请求一打临时对象"的代码

方向 C：提升启动速度（CDS + AOT）
  方向 A 和 B 是运行期调优，方向 C 是部署期调优
```

**JDK 17+ 的两个启动优化（不用改一行业务代码）：**

```bash
# 1. AppCDS — 共享类数据归档，启动快 20-40%
java -Xshare:dump -XX:SharedArchiveFile=app.jsa -cp app.jar
java -XX:SharedArchiveFile=app.jsa -jar app.jar

# 2. AOT 缓存 — 把 JIT 编译结果缓存下来
java -XX:AOTCache=app.aot -jar app.jar   # 首次运行自动生成
# 第二次启动直接加载，跳过 JIT 预热期
```

#### 8.6 调优工具箱速查

```
┌─────────────────┬────────────────────────────────────────┐
│  我想知道...      │  用什么                                │
├─────────────────┼────────────────────────────────────────┤
│  GC 在干嘛       │  jstat -gcutil <pid> 1s               │
│  GC 详细时间线    │  GC 日志（-Xlog:gc*）                  │
│  谁在分配内存     │  JFR → Memory → Allocations           │
│  对象为什么不回收  │  jcmd <pid> GC.heap_dump → MAT 分析    │
│  线程在干嘛       │  jstack <pid> 或 JFR → Threads        │
│  哪个锁最竞争     │  JFR → Lock Instances                 │
│  类加载是否泄漏    │  jstat -class <pid> 1s               │
│  CPU 热点在哪     │  JFR → Method Profiling               │
│  K8s Pod 总内存   │  RSS（不是堆大小！）                   │
│  堆外内存占用     │  NMT（-XX:NativeMemoryTracking=summary）│
└─────────────────┴────────────────────────────────────────┘
```

**NMT（Native Memory Tracking）——排查堆外内存的利器：**

```bash
# 启动时加参数
-XX:NativeMemoryTracking=summary

# 运行时查看
jcmd <pid> VM.native_memory summary

# 输出会告诉你：
#   - Java Heap：用的堆
#   - Class：类元数据（Metaspace）
#   - Thread：线程栈消耗（每个线程 ~1MB）
#   - Code：JIT 编译后的代码缓存
#   - GC：GC 数据结构
#   - Internal：JVM 内部（Direct Buffer 等）
# 把所有加起来 = 进程 RSS，任何一个异常增长都能定位
```

#### 8.7 总结：调优不靠记参数，靠这个流程

```
                                    调优储备
                                        │
                    三条防线：          ├── 业务代码优化
                    ① GC 日志          ├── JVM 参数调优
                    ② JFR 飞行记录      ├── GC 算法选择
                    ③ NMT 内存追踪      ├── OS 内核调优
                                        └── 框架/中间件调优


实际调优时，你不需要记得 700 多个 JVM 参数。
你只需要：

1. 知道应用属于哪类场景（吞吐优先 / 延迟优先 / 启动优先）
2. 搭好观测三件套（GC 日志 + JFR + NMT）
3. 从第一层（堆大小）开始，每层改一个参数
4. 改完看数据，有效保留，无效回滚
5. 90% 的问题在前两层就解决了（堆大小 + GC 选择）

如果前两层解决不了，大概率不是 JVM 的问题，
而是业务代码的问题（锁竞争、大对象、泄漏、N+1 查询...）
