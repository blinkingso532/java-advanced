# jstat 命令

jstat（JVM Statistics Monitoring Tool）用于监控和分析 Java 应用程序的运行状态。它通过读取 HotSpot 虚拟机内部的性能计数器，实时输出内存、GC、类加载、JIT 编译等指标。

---

## 一、基本语法

```bash
jstat -<option> <pid> [<interval_ms>] [<count>]
```

| 参数 | 说明 |
|------|------|
| `option` | 监控选项，如 `-gcutil`、`-gccapacity` 等 |
| `pid` | Java 进程的进程 ID（通过 `jps` 获取） |
| `interval_ms` | 采样间隔（毫秒），不指定则只输出一次 |
| `count` | 采样次数，不指定则无限输出 |

示例：

```bash
jstat -gcutil 12345 1000 10    # 每 1 秒采样一次 GC 使用率，共 10 次
jstat -gc 12345 2000            # 每 2 秒输出一次 GC 详细数据，无限循环
jstat -class 12345              # 输出一次类加载统计
```

---

## 二、常用选项

### 2.1 `-gcutil` — GC 使用率总览（最常用）

```bash
jstat -gcutil <pid> 1000
```

输出列：

```text
  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT     CGC    CGCT     GCT
  0.00  12.34  45.67  23.45  89.01  85.23  123     1.234   5      0.567    8      0.234    2.035
```

| 列名 | 全称 | 含义 | 解读 |
|------|------|------|------|
| **S0** | Survivor 0 | Survivor 0 区使用率（%） | 高说明存活对象多，可能在 Minor GC 间反复拷贝 |
| **S1** | Survivor 1 | Survivor 1 区使用率（%） | 同 S0，两者交替使用，只有一个有数据 |
| **E** | Eden | Eden 区使用率（%） | 持续攀升 → 即将触发 Minor GC；接近 100% → Minor GC 频繁 |
| **O** | Old | 老年代使用率（%） | 持续增长不回落 → 可能存在内存泄漏 |
| **M** | Metaspace | 元空间使用率（%） | 持续增长 → 动态类生成过多（CGLIB、动态代理） |
| **CCS** | Compressed Class Space | 压缩类空间使用率（%） | 与 M 相关，存放 Klass 指针 |
| **YGC** | Young GC | Minor GC 次数 | 增长越快 → 对象分配越频繁 |
| **YGCT** | Young GC Time | Minor GC 总耗时（秒） | YGCT / YGC = 平均每次 Minor GC 耗时 |
| **FGC** | Full GC | Full GC 次数 | 增长快 → 内存泄漏或老年代容量不足 |
| **FGCT** | Full GC Time | Full GC 总耗时（秒） | Full GC 通常伴随 STW，耗时过大需要关注 |
| **CGC** | Concurrent GC | 并发 GC（CMS/G1）次数 | G1 的并发标记周期次数 |
| **CGCT** | Concurrent GC Time | 并发 GC 总耗时（秒） | 并发阶段不 STW，但占用 CPU |
| **GCT** | Total GC Time | GC 总耗时（秒） | YGCT + FGCT + CGCT |

**常见判断：**

| 现象 | 可能原因 | 排查方向 |
|------|---------|---------|
| E 频繁从高到低，YGC 增速快 | 对象分配速率高 | 检查是否有频繁创建临时对象的热点代码 |
| O 持续增长不回落 | 内存泄漏 | dump 分析大对象和 GC Root 引用链 |
| M 持续增长 | 类加载泄漏 | 排查动态代理/CGLIB/JSP 编译 |
| FGCT 数值大 | Full GC 耗时过长 | 考虑升级 G1/ZGC 或增大堆 |
| FGC 增速快 | 老年代大对象分配多 | 检查大对象分配点，调优 `-XX:PretenureSizeThreshold` |

---

### 2.2 `-gc` — GC 详细统计

```bash
jstat -gc <pid> 1000
```

输出列：

```text
 S0C    S1C    S0U    S1U      EC       EU        OC         OU       MC       MU      CCSC   CCSU   YGC   YGCT   FGC   FGCT   CGC   CGCT   GCT
```

| 列名 | 含义 | 与 gcutil 差异 |
|------|------|---------------|
| S0C / S1C | Survivor 0/1 容量（KB） | 绝对容量而非百分比 |
| S0U / S1U | Survivor 0/1 已使用（KB） | 绝对使用量 |
| EC | Eden 容量（KB） | 绝对容量 |
| EU | Eden 已使用（KB） | 绝对使用量 |
| OC | 老年代容量（KB） | 绝对容量 |
| OU | 老年代已使用（KB） | 绝对使用量，**排查内存泄漏的关键指标** |
| MC | Metaspace 容量（KB） | 绝对容量 |
| MU | Metaspace 已使用（KB） | 绝对使用量 |
| CCSC / CCSU | 压缩类空间容量/使用量（KB） | — |

**适用场景**：需要知道**绝对内存占用量**时，如判断堆配比是否合理。

---

### 2.3 `-gccapacity` — 各区域容量

```bash
jstat -gccapacity <pid>
```

输出列：

```
 NGCMN    NGCMX     NGC     S0C   S1C       EC      OGCMN      OGCMX       OGC         OC       MCMN     MCMX      MC
```

| 列名 | 含义 |
|------|------|
| NGCMN / NGCMX | 新生代最小/最大容量（KB） |
| NGC | 新生代当前容量（KB） |
| OGCMN / OGCMX | 老年代最小/最大容量（KB） |
| OGC | 老年代当前容量（KB） |
| MCMN / MCMX | Metaspace 最小/最大容量（KB） |
| -CCS 后缀同族 | 压缩类空间对应指标 |

**适用场景**：检查各区域容量设定是否合理，观察 JVM 是否动态调整了区域大小。

---

### 2.4 `-gcnew` / `-gcold` / `-gcmetacapacity`

```bash
jstat -gcnew <pid>       # 新生代详情
jstat -gcold <pid>       # 老年代详情
jstat -gcmetacapacity <pid>  # Metaspace 详情
```

**适用场景**：聚焦某个特定区域深入排查。

---

### 2.5 `-class` — 类加载统计

```bash
jstat -class <pid>
```

输出列：

```
Loaded  Bytes  Unloaded  Bytes     Time
 12345  23456.7   123    456.7   12.345
```

| 列名 | 含义 |
|------|------|
| Loaded | 已加载类数量 |
| Bytes | 已加载类的字节数（KB） |
| Unloaded | 已卸载类数量 |
| Bytes | 已卸载类字节数（KB） |
| Time | 类加载耗时（秒） |

**适用场景**：排查类加载器泄漏——如果 Loaded 持续增长而 Unloaded 不增长，说明类只加载不卸载。

---

### 2.6 `-compiler` — JIT 编译统计

```bash
jstat -compiler <pid>
```

输出列：

```
Compiled Failed Invalid   Time   FailedType FailedMethod
   5678      10       0 123.45          1 java/lang/String foo
```

| 列名 | 含义 |
|------|------|
| Compiled | 已编译任务数 |
| Failed | 编译失败数 |
| Invalid | 无效/去优化计数 |
| Time | 编译耗时（秒） |
| FailedType / FailedMethod | 最后一次编译失败的类型和方法 |

**适用场景**：观察 JIT 编译是否正常，大面积编译失败需关注。

---

## 三、实战排查示例

### 场景 1：快速判断内存是否泄漏

```bash
# 每 5 秒采样一次，观察 O（老年代使用率）是否只升不降
jstat -gcutil <pid> 5000
```

**判断逻辑**：

- 如果 OU（绝对使用量）持续增长，Full GC 后也不回落 → **内存泄漏**
- 如果 OU 在 Full GC 后明显回落 → 正常，只是分配速率高

### 场景 2：GC 停顿频率排查

```bash
# 每秒采样，观察 YGC 和 FGC 的增量
jstat -gcutil <pid> 1000
```

**判断逻辑**：

- YGC 每秒增加 > 5 次 → Minor GC 过于频繁，考虑增大新生代
- FGC 非零且持续增长 → Full GC 有问题，优先分析 dump
- GCT 占运行时间比例 > 5% → GC 开销过高

### 场景 3：Metaspace OOM 预警

```bash
# 观察 M 列（Metaspace 使用率）
jstat -gcutil <pid> 1000
# 或看绝对用量
jstat -gcmetacapacity <pid> 1000
```

**判断逻辑**：

- MU 持续增长接近 MCMX → 即将 Metaspace OOM
- 常见于 Spring AOP 增强、CGLIB 动态代理、Groovy 动态脚本场景

### 场景 4：对象晋升速率

```bash
# 同时观察新生代和老年代
jstat -gc <pid> 2000
```

**判断逻辑**：

- 对比每次 Minor GC 后 OU 的增量 → 每次晋升到老年代的量
- OU 增量大 → 对象过早晋升，调大 Survivor 区或增加晋升年龄阈值（`-XX:MaxTenuringThreshold`）

### 场景 5：线上应急排查（不依赖 jstat 时的替代）

```bash
# 查看存活对象分布（不带 :live 更轻量，不触发 Full GC）
jmap -histo <pid> | head -30

# 查看堆配置
jinfo -flag +PrintGCDetails <pid>

# 打印线程栈
jstack <pid> | grep -A 20 "BLOCKED\|WAITING"
```

---

## 四、快速参考卡片

```
┌──────────────┬──────────────────────────────────────────────┐
│  监控维度     │  命令                                        │
├──────────────┼──────────────────────────────────────────────┤
│  GC 使用率    │  jstat -gcutil <pid> 1000                    │
│  GC 绝对量    │  jstat -gc <pid> 1000                        │
│  容量配置     │  jstat -gccapacity <pid>                     │
│  新生代       │  jstat -gcnew <pid>                          │
│  老年代       │  jstat -gcold <pid>                          │
│  Metaspace   │  jstat -gcmetacapacity <pid>                  │
│  类加载       │  jstat -class <pid>                          │
│  JIT 编译     │  jstat -compiler <pid>                       │
│  GC 历史      │  jstat -gcutil <pid> 1000 60 （60次×1秒）    │
└──────────────┴──────────────────────────────────────────────┘
```

**关键颜色信号（-gcutil 一眼判断）：**

| 指标 | 🟢 健康 | 🟡 关注 | 🔴 危险 |
|------|---------|---------|---------|
| E 使用率 | 波动正常 | — | —（波动是正常的） |
| O 使用率 | 稳定或缓慢上升 | 持续上升 | Full GC 后不回落 |
| M 使用率 | 稳定 | 持续小幅度上升 | 持续增长接近上限 |
| YGC/min | < 30 | 30~60 | > 60 |
| FGC | 0 | 偶尔 1~2 | 持续增长 |
| GCT/s | < 0.05 | 0.05~0.1 | > 0.1（GC 占运行时间 > 10%） |
