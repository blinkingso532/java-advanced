# Java GC 垃圾回收机制

> 深入覆盖 GC 基础理论、回收算法、屏障机制、收集器演进、JDK 17+ 推荐配置与调优实战。基础概览见 [JVM基本原理.md](JVM基本原理.md) 第四节。

---

## 一、垃圾回收基础理论

### 1.1 为什么需要 GC

手动内存管理（C/C++ 的 `malloc/free`）的三大问题：

| 问题 | 表现 | GC 如何解决 |
|------|------|------------|
| 忘记释放 | 内存泄漏，进程 OOM | 自动追踪不可达对象并回收 |
| 过早释放 | 悬挂指针（use-after-free），崩溃 | 对象只在不可达后才回收 |
| 重复释放 | double-free，堆损坏 | 回收由 JVM 唯一负责，不存在重复释放 |

GC 的代价：消耗 CPU 资源进行追踪和回收，可能产生 STW（Stop-The-World）停顿。现代收集器的演进方向就是**缩短 STW 时间**直至趋近于零。

### 1.2 可达性分析算法

**核心思想**：从一组固定的根对象（GC Roots）出发，沿引用链遍历，不可达的对象即为垃圾。

```
GC Roots
  │
  ├─→ Object A ──→ Object B ──→ Object C   ← 可达，存活
  │
  ├─→ Object D ──→ Object E                 ← 可达，存活
  │
  └─→ (无引用)   Object F ──→ Object G      ← 不可达，回收
```

**GC Roots 包含哪些？**

| GC Root 来源 | 说明 |
|-------------|------|
| 栈帧局部变量 | 当前所有线程的 Java 栈帧中引用的对象 |
| 静态引用 | 类的 static 字段引用的对象 |
| JNI 引用 | 本地方法栈中 JNI（Global JNI Handles）引用的对象 |
| 锁持有对象 | `synchronized` 监视器锁持有的对象 |
| 线程对象 | 活跃线程本身 |
| 类加载器 | 正在使用的 ClassLoader 对象 |
| JVM 内部引用 | 基本类型对应的 Class 对象、常驻异常对象、系统类加载器等 |

**为什么不用引用计数法？**

```java
Object a = new Object();
Object b = new Object();
a.ref = b;   // a 引用 b
b.ref = a;   // b 引用 a
a = null;    // a、b 互相引用，引用计数都不为 0
b = null;    // 但实际已无法访问 → 内存泄漏（引用计数无法回收）
```

引用计数法无法处理循环引用，所以 JVM 采用可达性分析。

### 1.3 四种引用类型

```java
// 1. 强引用（Strong Reference）—— 永不回收，除非不可达
Object strong = new Object();

// 2. 软引用（Soft Reference）—— 内存不足时回收，适合缓存
SoftReference<byte[]> cache = new SoftReference<>(new byte[1024 * 1024]);
byte[] data = cache.get();  // 可能返回 null（如果 GC 已回收）

// 3. 弱引用（Weak Reference）—— 下次 GC 时回收，适合临时映射
WeakReference<Key> weakKey = new WeakReference<>(new Key());
Key k = weakKey.get();  // GC 后可能返回 null

// 4. 虚引用（Phantom Reference）—— get() 始终返回 null，仅跟踪回收通知
PhantomReference<Object> phantom = new PhantomReference<>(new Object(), queue);
phantom.get();  // 始终 null
// 对象被回收后，引用入 ReferenceQueue，通过 queue.poll() 获知
```

| 引用类型 | 回收时机 | 典型用途 |
|---------|---------|---------|
| 强引用 | 不可达时才回收 | 普通引用 |
| 软引用 | 内存不足时回收 | Guava Cache、图片缓存 |
| 弱引用 | 下次 GC 时回收 | WeakHashMap、ThreadLocal 的 Entry |
| 虚引用 | 随时可回收 | 跟踪 GC 回收、管理堆外内存（Cleaner） |

**引用队列（ReferenceQueue）**：软/弱/虚引用可以关联一个 ReferenceQueue，对象被回收后引用对象自身会入队，程序通过 `queue.poll()` 感知回收事件。虚引用必须配合 ReferenceQueue 使用，否则无意义。

### 1.4 对象的最终化（finalize）—— 不推荐使用

```java
@Override
protected void finalize() throws Throwable {
    super.finalize();
    // "自救"：重新将自己引用到 GC Roots 链上
    SAVE_HOOK = this;
}
```

**为什么避免使用 finalize？**

1. **执行时间不确定**：GC 触发时间不确定，`finalize()` 何时执行无法预测
2. **可能不执行**：JVM 不保证 `finalize()` 一定被调用，程序退出时未执行的直接跳过
3. **性能开销大**：每个覆盖 `finalize()` 的对象需要额外在 Finalizer 队列中注册和排队处理
4. **安全风险**：`finalize()` 中抛出异常会被静默吞掉，且对象可能"复活"导致状态混乱
5. **JDK 9 已标记废弃**：`Object.finalize()` 标记为 `@Deprecated(forRemoval=true)`

**替代方案**：使用 `try-with-resources` + `AutoCloseable`，或 `Cleaner`（JDK 9+）管理资源释放。

---

## 二、分代模型与对象生命周期

### 2.1 分代假说

分代收集的理论基础来自经验统计：

| 假说 | 内容 | 设计影响 |
|------|------|---------|
| **弱代假说**（Weak Generational Hypothesis） | 绝大多数对象朝生夕死 | 新生代用复制算法，只存活少量对象需复制 |
| **强代假说**（Strong Generational Hypothesis） | 熬过多次 GC 的对象越难回收 | 老年代用标记-整理，避免复制大开销 |
| **跨代引用假说** | 跨代引用相对于同代引用极少 | 记忆集（Remembered Set）只记录跨代引用，避免全堆扫描 |

### 2.2 堆内存布局

**传统分代布局（Serial / Parallel / CMS）：**

```
┌────────────────────────────────────────────────────────┐
│                        堆 Heap                         │
│  ┌───────────────────────────┬───────────────────────┐ │
│  │     新生代 Young           │    老年代 Old/Tenured │ │
│  │  ┌───────┬──────┬──────┐  │                       │ │
│  │  │ Eden  │ S0   │ S1   │  │                       │ │
│  │  │  80%  │ 10%  │ 10%  │  │                       │ │
│  │  └───────┴──────┴──────┘  │                       │ │
│  │  -XX:NewRatio=2           │                       │ │
│  │  (Old:Young = 2:1)        │                       │ │
│  └───────────────────────────┴───────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

**G1 Region 化布局：**

```
┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
│ E  │ E  │ S  │ O  │ O  │ O  │ H  │ E  │ O  │ E  │
├────┼────┼────┼────┼────┼────┼────┼────┼────┼────┤
│ E  │ O  │ O  │ S  │ E  │ E  │ O  │ O  │ E  │ O  │
└────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
E = Eden Region    S = Survivor Region
O = Old Region     H = Humongous Region（大对象）
每个 Region 大小：1MB ~ 32MB（自动选择，2 的幂次）
```

**ZGC 动态布局：**

```
ZGC 不做传统分代（JDK 21 分代模式除外），
Region 大小动态：2MB / 32MB / N×2MB
┌──────┬──────────────┬──────┬──────────┬──────┐
│ small│   medium     │small │  large   │small │
│ 2MB  │    32MB      │ 2MB  │ N×2MB    │ 2MB  │
└──────┴──────────────┴──────┴──────────┴──────┘
small  → < 256KB 的对象
medium → 256KB ~ 4MB 的对象
large  → > 4MB 的对象（独占连续 Region 组）
```

### 2.3 对象分配流程

```
新对象分配请求
  │
  ├─ 是否大对象？
  │    ├─ 是 → 直接进入老年代/大对象 Region（避免 Eden 区复制开销）
  │    └─ 否 ↓
  │
  ├─ TLAB（Thread Local Allocation Buffer）是否可用？
  │    ├─ 是 → 在 TLAB 中分配（无锁，CAS 操作）→ 成功返回
  │    └─ 否 ↓
  │
  ├─ Eden 区 CAS 分配
  │    ├─ 成功 → 返回
  │    └─ 失败 ↓
  │
  └─ 触发 Minor GC → 回收后重试分配
```

**TLAB 详解**：

- JVM 在 Eden 区为每个线程预分配一小块私有缓冲区（TLAB），线程在 TLAB 中分配对象无需加锁
- TLAB 通常很小（默认 Eden 的 1%），分配满后回 Eden 区申请新 TLAB 或直接 CAS 分配
- `-XX:+UseTLAB` 默认开启，`-XX:TLABSize` 可调整大小

**对象晋升规则**：

| 条件 | 行为 |
|------|------|
| 年龄达到阈值 | 晋升老年代，`-XX:MaxTenuringThreshold=15`（默认） |
| Survivor 区空间不足 | 动态年龄计算：同龄对象总大小 > Survivor 空间 50% 则该年龄及以上全部晋升 |
| 大对象 | 直接进入老年代，`-XX:PretenureSizeThreshold=1M`（仅 Serial/ParNew 有效） |

### 2.4 GC 事件分类

| GC 类型 | 回收区域 | 触发条件 | STW 特点 |
|---------|---------|---------|---------|
| **Minor GC / Young GC** | 新生代 | Eden 区满 | 通常短（几 ms ~ 几十 ms） |
| **Major GC** | 老年代 | 老年代满 | 时间较长 |
| **Full GC** | 整个堆 + Metaspace | 多种触发条件 | 最长，应尽量避免 |
| **Concurrent GC** | 全堆/部分 | 后台并发运行 | STW 极短（ZGC < 1ms） |

**Full GC 触发条件**：

1. 老年代空间不足（大对象晋升、长期存活对象积累）
2. Metaspace 空间不足（`-XX:MaxMetaspaceSize` 达上限）
3. 显式调用 `System.gc()`（可用 `-XX:+DisableExplicitGC` 屏蔽）
4. 晋升失败（Promotion Failure）：Minor GC 后 Survivor 放不下存活对象，老年代也放不下
5. 空间分配担保失败（Ergonomics 判断老年代可用连续空间 < 历次晋升平均大小）

---

## 三、基础垃圾回收算法

### 3.1 标记-清除（Mark-Sweep）

```
标记前：                标记后：               清除后：
┌──┬──┬──┬──┬──┐     ┌──┬──┬──┬──┬──┐     ┌──┬  ┬──┬  ┬──┐
│A │B │C │D │E │     │A✓│B✓│C │D✓│E │     │A │  │C │  │E │
└──┴──┴──┴──┴──┘     └──┴──┴──┴──┴──┘     └──┴  ┴──┴  ┴──┘
                      A/B/D 存活          碎片化空间
                      C/E 可回收
```

| 优点 | 缺点 |
|------|------|
| 实现简单 | 碎片化：清除后内存不连续，大对象可能分配失败 |
| 不需要移动对象 | 分配效率低：空闲列表维护开销大 |
| | 分配时可能触发压缩 |

**应用**：CMS 的老年代回收（基于此算法，已移除）。

### 3.2 标记-整理（Mark-Compact）

```
标记后：               整理后：
┌──┬  ┬──┬  ┬──┐     ┌──┬──┬──┬  ┬  ┬  ┐
│A │  │C │  │E │     │A │C │E │  │  │  │
└──┴  ┴──┴  ┴──┘     └──┴──┴──┴  ┴  ┴  ┘
碎片化空间              连续空闲空间
```

| 优点 | 缺点 |
|------|------|
| 无碎片 | 移动对象需要更新所有引用，开销大 |
| 分配快（指针碰撞） | 移动过程需要 STW |
| 内存利用率高 | 停顿时间与存活对象数量成正比 |

**应用**：Serial Old、Parallel Old、G1 的 Full GC。

### 3.3 复制算法（Copying）

```
复制前（From）：              复制后（To）：
┌──┬  ┬──┬  ┬──┐            ┌──┬──┬──┬  ┬  ┐
│A │  │C │  │E │   ──→     │A │C │E │  │  │
└──┴  ┴──┴  ┴──┘            └──┴──┴──┴  ┴  ┘
From 空间                    To 空间（连续）
存活对象 A/C/E 复制到 To     From 清空后变为新 To
```

| 优点 | 缺点 |
|------|------|
| 无碎片 | 可用空间减半（需要两块等大空间） |
| 分配快（指针碰撞） | 存活对象多时复制开销大 |
| 停顿时间与存活对象成正比 | 适合存活率低的场景（新生代） |

**Eden + S0/S1 优化**：不是 1:1 平分，而是 Eden:S0:S1 = 8:1:1，只用 10% 空间做 Survivor 互换，空间利用率 90%。

### 3.4 分代收集（Generational）

实际收集器不只用一种算法，而是根据分代特征组合：

| 区域 | 特征 | 算法选择 |
|------|------|---------|
| 新生代 | 对象存活率低（~10%） | 复制算法（只复制少量存活对象） |
| 老年代 | 对象存活率高 | 标记-清除 或 标记-整理 |

### 3.5 算法对比总结

| 维度 | 标记-清除 | 标记-整理 | 复制 |
|------|----------|----------|------|
| 空间碎片 | ✅ 有 | ❌ 无 | ❌ 无 |
| 内存开销 | 无额外开销 | 无额外开销 | 空间减半 |
| 移动对象 | ❌ 不移动 | ✅ 移动 | ✅ 移动 |
| 引用更新 | 不需要 | 需要 | 需要 |
| 分配方式 | 空闲列表 | 指针碰撞 | 指针碰撞 |
| 适合场景 | 存活对象多 | 存活对象多 | 存活对象少 |
| STW 时长 | 较短（仅标记+清除） | 较长（标记+移动+更新引用） | 与存活对象成正比 |

---

## 四、三色标记法与屏障机制

### 4.1 三色标记法原理

现代 GC 使用三色标记法（Tri-color Marking）进行并发标记：

| 颜色 | 含义 | 状态 |
|------|------|------|
| ⚪ 白色 | 尚未被访问 | 回收候选，标记结束后仍为白色 → 被回收 |
| 🔘 灰色 | 已被访问，但引用未处理完 | 待处理，还需扫描其引用字段 |
| ⚫ 黑色 | 已被访问，且引用已全部处理完 | 确定存活，不会被重新扫描 |

```
标记过程（BFS/DFS 遍历引用图）：

初始：所有对象白色
  │
  ▼
GC Roots → 标记灰色
  │
  ▼
取出灰色对象 → 扫描其引用 → 引用对象标灰 → 自身标黑
  │
  ▼
重复直到无灰色对象
  │
  ▼
剩余白色对象 = 垃圾 → 回收
```

### 4.2 漏标问题与 Wilson 理论

并发标记时，应用线程和 GC 线程同时运行，可能导致**漏标**：正在使用的对象被错误回收。

**漏标的两个必要条件**（Wilson 理论，必须同时满足）：

```
条件 1：灰色对象断开了对白色对象的引用（删除引用）
条件 2：黑色对象新增了对该白色对象的引用（新增引用）
```

```
时间线：

T1: 灰色G → 白色W     （G 尚未扫描 W）
T2: 黑色B → 新增引用 → 白色W  （应用线程操作）
T3: 灰色G 断开 → 白色W        （应用线程操作）
T4: G 扫描完毕变黑，不再扫描 W
    W 没有被任何灰色对象引用 → 保持白色 → 被错误回收！
```

**解决思路**：打破两个条件中的任意一个即可。

### 4.3 增量更新（Incremental Update）— CMS 采用

**打破条件 2**：记录黑色对象新增的引用。

```
写屏障伪代码：
  void before_field_write(obj, field, new_val) {
    if (obj.isBlack() && new_val.isWhite()) {
      // 黑色引用了白色 → 记录 obj 为灰色（回退为灰色）
      mark_gray(obj);  // obj 重新加入标记栈
    }
  }
```

- 黑色对象一旦新增引用，就被**回退为灰色**，重新扫描
- 重新标记阶段（Remark）需要 STW，处理这些灰色对象
- CMS 使用此方案，Remark 阶段可能较长

### 4.4 原始快照 SATB — G1 采用

**打破条件 1**：记录灰色对象删除的引用（在删除前保存快照）。

```
写屏障伪代码：
  void before_field_write(obj, field, old_val) {
    if (old_val != null && old_val.isWhite()) {
      // 灰色→白色引用被断开前，记录 old_val
      satb_queue.push(old_val);  // 入 SATB 队列
    }
  }
```

- 在引用被删除前，把被删引用指向的白色对象记录下来
- 重新标记阶段从 SATB 队列取出这些对象，以它们为根重新扫描
- G1 使用此方案，标记阶段并发度更高

**SATB vs 增量更新对比**：

| 维度 | 增量更新（CMS） | SATB（G1） |
|------|---------------|-----------|
| 记录内容 | 新增引用（黑色→白色） | 删除引用（灰色→白色断开前） |
| 记录量 | 可能很多（应用频繁写引用） | 相对较少（只记录删除操作） |
| 重新标记 STW | 较长（需处理更多灰色对象） | 较短（只处理 SATB 队列） |
| 浮动垃圾 | 较少 | 较多（删除前的快照中有些对象实际已不可达） |

### 4.5 读屏障（Load Barrier）— ZGC / Shenandoah 采用

写屏障在**引用写入时**拦截，读屏障在**引用读取时**拦截。

**ZGC 读屏障自愈**：

```c
// 读屏障伪代码
Object* read_barrier(Object** ref) {
    Object* obj = *ref;
    if (is_colored_pointer_bad(obj)) {
        // 对象已被移动，修正引用（自愈）
        Object* new_addr = forwarding_address(obj);
        *ref = new_addr;   // 修正引用指针
        return new_addr;
    }
    return obj;
}
```

- 每次通过引用访问对象时，检查指针颜色标记
- 如果对象已被 GC 移动，读屏障自动修正引用（自愈），不影响应用逻辑
- 代价：每次对象引用读取都有额外开销（约 4% 吞吐量影响）

**Shenandoah 读屏障（Brooks 指针）**：

```c
// 每个对象头前有一个 Brooks 指针（指向自身或转发地址）
Object* read_barrier(Object* obj) {
    Object* brooks_ptr = obj->brooks_pointer;
    if (brooks_ptr != obj) {
        return brooks_ptr;  // 返回新地址
    }
    return obj;
}
```

### 4.6 写屏障 vs 读屏障对比

| 维度 | 写屏障 | 读屏障 |
|------|--------|--------|
| 触发时机 | 引用字段被赋值时 | 通过引用访问对象时 |
| 开销位置 | 写操作 | 读操作（频率远高于写） |
| 适合操作 | 记录引用变更（SATB/增量更新） | 检查对象是否被移动并自愈 |
| 采用者 | CMS、G1 | ZGC、Shenandoah |
| 吞吐影响 | 较小 | 稍大（~4%） |
| 延迟收益 | 一般 | 极大（支持并发移动对象） |

**关键认知**：读屏障是 ZGC/Shenandoah 实现亚毫秒级 STW 的基础——它允许 GC 在应用运行的同时移动对象，读屏障负责透明地修正引用。

---

## 五、垃圾收集器详解

### 5.1 收集器总览与演进

```
JDK 版本     收集器演进
─────────────────────────────────────────────────
JDK 1.2      Serial / Serial Old
JDK 1.3      Parallel Scavenge（吞吐量优先）
JDK 1.4.2    CMS（Concurrent Mark-Sweep，低延迟先驱）
JDK 5        Parallel Old（配合 Parallel Scavenge）
JDK 7u4      G1 正式支持
JDK 9        G1 成为默认；CMS 标记 Deprecated
JDK 11       ZGC 实验性引入；Shenandoah 实验性引入
JDK 12       Shenandoah 正式引入；G1 增强（可中断混合回收）
JDK 14       CMS 正式移除
JDK 15       ZGC 生产可用
JDK 17       G1 默认；ZGC/Shenandoah 可选
JDK 21       分代 ZGC 正式引入（-XX:+ZGenerational）
```

### 5.2 Serial / Serial Old

```
                    ┌──────────┐
  应用线程 ─────→   │  STW     │ ─────→ 应用恢复
                    │ Serial   │
                    │ GC 线程  │
                    └──────────┘
```

| 特性 | Serial | Serial Old |
|------|--------|-----------|
| 回收区域 | 新生代 | 老年代 |
| 算法 | 复制 | 标记-整理 |
| 线程数 | 1 | 1 |
| STW | 是 | 是 |
| 适用 | 客户端模式、小堆（< 100MB） | 同左 |

**启用参数**：`-XX:+UseSerialGC`

### 5.3 Parallel Scavenge / Parallel Old

```
                    ┌──────────────┐
  应用线程 ─────→   │     STW      │ ─────→ 应用恢复
                    │ ┌─┐┌─┐┌─┐┌─┐│
                    │ │G││C││G││C││  ← 多个 GC 线程并行
                    │ └─┘└─┘└─┘└─┘│
                    └──────────────┘
```

| 特性 | Parallel Scavenge | Parallel Old |
|------|-------------------|-------------|
| 回收区域 | 新生代 | 老年代 |
| 算法 | 复制 | 标记-整理 |
| 线程数 | 多个（= CPU 核数） | 多个 |
| 目标 | **吞吐量优先** | 配合 PS |
| STW | 是 | 是 |

**关键参数**：
- `-XX:+UseParallelGC`：使用 Parallel Scavenge + Serial Old
- `-XX:+UseParallelOldGC`：使用 Parallel Scavenge + Parallel Old
- `-XX:MaxGCPauseMillis=200`：最大停顿时间目标（JVM 尽力达到，不保证）
- `-XX:GCTimeRatio=99`：吞吐量目标（GC 时间占比 = 1/(1+99) = 1%）

**适用场景**：批处理、离线计算、科学计算——吞吐量比延迟更重要。

### 5.4 CMS（Concurrent Mark-Sweep）—— JDK 14 已移除

CMS 是第一款真正意义上的**并发**收集器，面试仍高频。

```
应用线程 ──→ ┃ 初始标记 ┃     ┃ 并发标记 ┃ ┃ 重新标记 ┃     ┃ 并发清除 ┃ ──→
             ┃  STW    ┃     ┃  并发    ┃ ┃  STW    ┃     ┃  并发   ┃
             ┃  极短    ┃     ┃         ┃ ┃  较短    ┃     ┃         ┃
```

| 阶段 | STW？ | 做什么 |
|------|-------|--------|
| 初始标记 | 是 | 标记 GC Roots 直接引用的对象（速度极快） |
| 并发标记 | 否 | 从 GC Roots 遍历整个引用链（最耗时，与应用并发） |
| 重新标记 | 是 | 处理并发标记期间引用变更（增量更新），STW 时间较长 |
| 并发清除 | 否 | 清除白色对象（与应用并发） |

**CMS 的致命问题**：

| 问题 | 说明 |
|------|------|
| 碎片化 | 标记-清除不压缩，长期运行后碎片严重，大对象分配失败 |
| 浮动垃圾 | 并发标记期间新产生的垃圾只能等下次 GC 回收 |
| Concurrent Mode Failure | 老年代预留空间不足（`-XX:CMSInitiatingOccupancyFraction`），退回 Serial Old 全停顿 |
| CPU 敏感 | 并发阶段占用 CPU，默认启动 (CPU核数+3)/4 个 GC 线程 |

**为什么被移除**：G1 在各方面超越 CMS，且 CMS 的维护成本高（代码复杂、难以适配模块化）。

### 5.5 G1（Garbage-First）—— JDK 9 默认，JDK 17+ 主力

#### 5.5.1 Region 机制

G1 将堆划分为大小相等的 Region（1MB ~ 32MB），每个 Region 可以动态扮演不同角色：

```
┌────┬────┬────┬────┬────┬────┬────┬────┐
│ E  │ E  │ S  │ O  │ [] │ O  │ E  │ H  │
└────┴────┴────┴────┴────┴────┴────┴────┘
 E=Eden  S=Survivor  O=Old  []=Free  H=Humongous

Region 角色在 GC 后可以转换：
  Eden → Free（Minor GC 后释放）
  Free → Old（对象晋升）
  Free → Humongous（大对象分配）
```

**Humongous 对象**：大小 ≥ Region 容量 50% 的对象直接分配在连续的 Humongous Region 中。

#### 5.5.2 回收流程

```
┌───────────────────────────────────────────────────┐
│              G1 回收周期                            │
│                                                    │
│  ┌─────────── Young GC ──────────┐                │
│  │  只回收 Eden + Survivor Region │  ← 频率高     │
│  │  STW，复制存活对象到新 Region   │                │
│  └───────────────────────────────┘                │
│         │                                          │
│         │ 老年代占比达 IHOP 阈值                    │
│         ▼                                          │
│  ┌────── 并发标记周期（类似 CMS）──────┐           │
│  │ 初始标记(STW) → 并发标记 →          │           │
│  │ 重新标记(STW) → 独占清理(STW)       │           │
│  └──────────────────────────────────┘           │
│         │                                          │
│         ▼                                          │
│  ┌────── 混合回收（Mixed GC）──────┐              │
│  │ 回收全部新生代 + 选中的老年代 Region │           │
│  │ （选择回收收益最大的 Region）       │ ← G1 名字由来│
│  │ 多轮 Mixed GC 直到回收收益不足     │              │
│  └──────────────────────────────────┘              │
│                                                    │
│  如果 Mixed GC 速度跟不上分配速度 → Full GC(Serial) │
└───────────────────────────────────────────────────┘
```

**IHOP（Initiating Heap Occupancy Percent）**：触发并发标记的老年代占比阈值，默认 45%。G1 会根据历史数据自适应调整（`-XX:+G1UseAdaptiveIHOP`，默认开启）。

#### 5.5.3 关键参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-XX:+UseG1GC` | JDK 9+ 默认 | 启用 G1 |
| `-XX:MaxGCPauseMillis=200` | 200ms | 目标最大停顿时间（软目标，G1 尽力达到） |
| `-XX:G1HeapRegionSize` | 自动 | Region 大小（1/2/4/8/16/32MB，JVM 自动选择） |
| `-XX:InitiatingHeapOccupancyPercent=45` | 45% | 触发并发标记的堆占用率 |
| `-XX:G1MixedGCCountTarget=8` | 8 | 混合回收目标次数 |
| `-XX:G1MixedGCLiveThresholdPercent=85` | 85% | Region 存活对象占比超过此值不纳入混合回收 |
| `-XX:G1ReservePercent=10` | 10% | 保留空间防止晋升失败 |

#### 5.5.4 JDK 12+ 增强

| 特性 | JDK 版本 | 说明 |
|------|---------|------|
| 可中断混合回收 | 12 | 如果混合回收超过停顿目标，可中断当前回收，留到下次继续 |
| 即时回归（Prompt Reclaim） | 12 | 并发标记期间发现完全空闲的 Region 立即回收，不必等混合回收 |
| NUMA 感知 | 14 | G1 在 NUMA 架构上自动优化 Region 分配 |

### 5.6 Shenandoah —— Red Hat 的低延迟收集器

#### 核心机制：Brooks 指针 + 读屏障

```
堆中每个对象头部前有一个额外的 Brooks 指针（转发指针）：

正常状态：                    GC 移动对象后：
┌─────────────────┐          ┌─────────────────┐
│ Brooks → self   │          │ Brooks → 新地址   │──→ ┌──────────────┐
├─────────────────┤          ├─────────────────┤    │ 对象实际数据   │
│ 对象实际数据     │          │ 对象实际数据(旧) │    └──────────────┘
└─────────────────┘          └─────────────────┘
```

- 读屏障检查 Brooks 指针是否指向自身
- 如果不指向自身，说明对象已被移动，读屏障返回新地址
- 应用线程和 GC 线程可以并发移动对象

**Shenandoah 回收阶段**：

```
初始标记(STW) → 并发标记 → 最终标记(STW) → 并发清理 → 并发疏散(移动) → 初始更新引用(STW) → 并发更新引用 → 最终更新引用(STW) → 并发清理
```

| 参数 | 说明 |
|------|------|
| `-XX:+UseShenandoahGC` | 启用 Shenandoah |
| `-XX:ShenandoahGCHeuristics=adaptive` | 启发式策略（adaptive/static/compact/aggressive） |
| `-XX:ShenandoahGarbageThreshold=60%` | Region 垃圾占比阈值 |

**与 ZGC 对比**：

| 维度 | Shenandoah | ZGC |
|------|-----------|-----|
| 转发机制 | Brooks 指针（对象头额外 8 字节） | 染色指针（指针中嵌入标记位） |
| 屏障 | 读屏障 + 写屏障 | 读屏障 |
| 堆大小限制 | 无特殊限制 | 4TB / 16TB / 64TB（取决于平台） |
| 内存开销 | 每对象 +8 字节 | 无额外对象头开销 |
| 商用支持 | Red Hat / Fedora | Oracle / OpenJDK |

### 5.7 ZGC —— JDK 21+ 推荐的低延迟收集器

#### 5.7.1 染色指针机制

ZGC 的核心创新：在 64 位指针中借用几个 bit 存储 GC 元数据，无需修改对象头。

```
64 位指针布局（Linux x86_64）：
┌──────────────────────────────────────────────────────────┐
│ 63  42 41 40 39 38 37 36                0                │
│ ┌───┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──────────────────┐           │
│ │未用││R0││R1││M0││M1││GC││    对象偏移地址     │          │
│ └───┘└──┘└──┘└──┘└──┘└──┘└──────────────────────┘       │
│       │    │    │    │    │    │                          │
│       │    │    │    │    │    └── GC 颜色（Remapped/Marked0/Marked1）│
│       │    │    │    │    └─── Marked1 标记位             │
│       │    │    └──────── Marked0 标记位                  │
│       │    └───────────── Remapped1（最终标记位）          │
│       └────────────────── Remapped0                       │
└──────────────────────────────────────────────────────────┘

为什么能借用这些位？
  - Linux x86_64 实际只使用 48 位虚拟地址（256TB 空间）
  - 高 16 位（47-63）未使用，可以安全借给 GC
  - 通过多重映射（multimap）技术，同一物理内存映射到多个虚拟地址，
    不同颜色标记对应不同的虚拟地址视图
```

**三种颜色状态**：

| 颜色状态 | 含义 |
|---------|------|
| `Remapped` | 正常状态，对象不在当前 GC 周期中被标记 |
| `Marked0` | 当前 GC 周期的标记状态 0 |
| `Marked1` | 当前 GC 周期的标记状态 1 |

每次 GC 周期交替使用 Marked0 和 Marked1，避免清除标记位——直接切换视图即可。

#### 5.7.2 读屏障自愈

```java
// Java 伪代码：读屏障的实际效果
Object ref = obj.field;  // 加载引用

// 读屏障插入的检查（JIT 生成的内联代码，~几条指令）
if (ref.color != current_gc_color) {
    // 情况 1：ref 还没被标记 → 标记为灰色，加入标记栈
    // 情况 2：ref 已被移动 → 通过转发表查找新地址，修正 obj.field（自愈）
    ref = heal_pointer(ref);
    obj.field = ref;  // 自愈：修正引用，下次访问不再触发屏障
}
```

**自愈的意义**：同一引用的读屏障只触发一次，修正后后续访问零开销。

#### 5.7.3 ZGC 回收流程

```
┌──────────────────────────────────────────────────────────────┐
│                    ZGC 回收周期                               │
│                                                              │
│  ┌──────── STW ────────┐                                    │
│  │ 初始标记（< 1ms）     │ ← 只标记 GC Roots 直接引用        │
│  └─────────────────────┘                                    │
│           │                                                  │
│  ┌──────── 并发 ────────┐                                    │
│  │ 并发标记              │ ← 读屏障发现未标记对象，加入标记栈  │
│  └─────────────────────┘                                    │
│           │                                                  │
│  ┌──────── STW ────────┐                                    │
│  │ 再标记（< 1ms）       │ ← 处理标记栈中剩余条目             │
│  └─────────────────────┘                                    │
│           │                                                  │
│  ┌──────── 并发 ────────┐                                    │
│  │ 并发转移准备          │ ← 选择要回收的 Region，选存活率低的 │
│  └─────────────────────┘                                    │
│           │                                                  │
│  ┌──────── STW ────────┐                                    │
│  │ 初始转移（< 1ms）     │ ← 只转移 Roots 直接引用的对象      │
│  └─────────────────────┘                                    │
│           │                                                  │
│  ┌──────── 并发 ────────┐                                    │
│  │ 并发转移              │ ← 转移其余存活对象到新 Region       │
│  │ 读屏障自愈            │   读屏障自动修正引用               │
│  └─────────────────────┘                                    │
│                                                              │
│  所有 STW 阶段均 < 1ms，与堆大小无关                          │
└──────────────────────────────────────────────────────────────┘
```

#### 5.7.4 分代 ZGC（JDK 21+）

非分代 ZGC 每次回收扫描全堆，对于年轻代大量朝生夕死对象效率不高。JDK 21 引入分代 ZGC：

```
┌─────────────────────────────────────────────────┐
│              分代 ZGC 堆布局                      │
│                                                   │
│  ┌──────────────┐    ┌────────────────────────┐  │
│  │  年轻代 Region │    │    老年代 Region         │  │
│  │  （频繁回收）  │    │    （较少回收）          │  │
│  └──────────────┘    └────────────────────────┘  │
│                                                   │
│  各自独立的染色指针视图                            │
│  各自独立的记忆集（Remembered Set）                │
│  年轻代回收不扫描老年代（通过 RSet 找跨代引用）     │
│  老年代回收频率远低于年轻代                         │
└─────────────────────────────────────────────────┘
```

| 参数 | 说明 |
|------|------|
| `-XX:+UseZGC` | 启用 ZGC |
| `-XX:+ZGenerational` | 启用分代模式（JDK 21+，强烈推荐） |
| `-XX:ZAllocationSpikeTolerance=2` | 分配尖峰容忍度（越大越保守预分配） |
| `-XX:ZFragmentationLimit=5%` | 碎片率上限，超过触发回收 |

**分代 vs 非分代 ZGC 对比**：

| 维度 | 非分代 ZGC | 分代 ZGC |
|------|-----------|---------|
| 回收范围 | 每次全堆 | 年轻代频繁，老年代按需 |
| 吞吐量 | 中等 | 更高（减少全堆扫描） |
| 分配暂停 | 可能有 | 极少 |
| 复杂度 | 较低 | 较高（需维护 RSet） |
| 推荐度 | JDK 15-20 | JDK 21+ **推荐** |

### 5.8 收集器组合与兼容矩阵

| 新生代 \ 老年代 | Serial Old | Parallel Old | CMS | G1 | ZGC | Shenandoah |
|---------------|-----------|-------------|-----|----|----|-----------|
| Serial | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Parallel Scavenge | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| ParNew | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ |
| G1（全堆） | — | — | — | ✅ | — | — |
| ZGC（全堆） | — | — | — | — | ✅ | — |
| Shenandoah（全堆） | — | — | — | — | — | ✅ |

> G1 / ZGC / Shenandoah 不区分新生代老年代收集器，各自独立管理全堆。

---

## 六、JDK 17+ 推荐 GC 策略与配置

### 6.1 JDK 17 默认 GC 行为

JDK 17 **默认使用 G1 GC**（`-XX:+UseG1GC`）。如果不显式指定 GC，行为如下：

| 堆大小 | 默认行为 |
|--------|---------|
| ≤ 1792MB | G1 Region = 1MB，MaxGCPauseMillis = 200ms |
| 1792MB ~ 3584MB | G1 Region = 2MB |
| 3584MB ~ 适配 | G1 Region 按堆大小自动增大 |

### 6.2 G1 推荐配置

**适用场景**：堆 < 32GB，延迟容忍 100-200ms，通用业务应用。

```bash
# 基础配置（最常用）
java -XX:+UseG1GC \
     -Xms4g -Xmx4g \                    # 初始堆 = 最大堆，避免扩缩容停顿
     -XX:MaxGCPauseMillis=100 \          # 目标停顿 100ms（根据业务调整）
     -XX:G1HeapRegionSize=8m \           # 4GB 堆建议 8MB Region
     -XX:InitiatingHeapOccupancyPercent=40 \  # 提前触发并发标记
     -XX:+ParallelRefProcEnabled \       # 并行处理 Reference 对象
     -jar app.jar

# 进阶配置（低延迟优化）
java -XX:+UseG1GC \
     -Xms8g -Xmx8g \
     -XX:MaxGCPauseMillis=50 \           # 更激进的停顿目标
     -XX:G1HeapRegionSize=16m \
     -XX:InitiatingHeapOccupancyPercent=35 \
     -XX:+ParallelRefProcEnabled \
     -XX:G1MixedGCCountTarget=16 \       # 更多轮次混合回收，每轮更短
     -XX:G1MixedGCLiveThresholdPercent=80 \  # 放宽混合回收阈值
     -XX:G1ReservePercent=15 \           # 更多保留空间防晋升失败
     -XX:+UseLargePages \                # 大页内存减少 TLB miss
     -jar app.jar
```

**G1 调优原则**：

1. **不要设太小的 MaxGCPauseMillis**：过小会导致 G1 缩小每次回收的 Region 数量，GC 更频繁，总吞吐量下降
2. **-Xms = -Xmx**：避免堆动态扩缩容的开销
3. **避免显式设置新生代大小**（`-Xmn`）：G1 会根据停顿目标自动调整新生代 Region 数，手动设置会破坏自适应
4. **关注 Mixed GC**：如果 Mixed GC 频繁退化为 Full GC，增大 IHOP 或增大堆

### 6.3 ZGC 推荐配置

**适用场景**：大堆（> 16GB）、低延迟要求（< 10ms）、实时交易系统、微服务网关。

```bash
# JDK 17 ZGC 配置
java -XX:+UseZGC \
     -Xms8g -Xmx8g \
     -XX:ZAllocationSpikeTolerance=2 \   # 预留更多空间应对分配尖峰
     -XX:ConcGCThreads=2 \               # 并发 GC 线程数（默认按 CPU 核数自动设置）
     -jar app.jar

# JDK 21 分代 ZGC 配置（推荐）
java -XX:+UseZGC \
     -XX:+ZGenerational \                # 启用分代模式
     -Xms8g -Xmx8g \
     -XX:ZAllocationSpikeTolerance=3 \
     -XX:SoftMaxHeapSize=6g \            # 软上限：JVM 尽量将堆使用控制在此值内
     -jar app.jar

# JDK 21 超大堆配置
java -XX:+UseZGC \
     -XX:+ZGenerational \
     -Xms64g -Xmx64g \                  # TB 级堆也支持
     -XX:ZAllocationSpikeTolerance=5 \
     -XX:SoftMaxHeapSize=48g \
     -XX:+UseLargePages \
     -jar app.jar
```

**ZGC 调优原则**：

1. **JDK 21+ 务必用 `-XX:+ZGenerational`**：分代模式吞吐量显著提升
2. **设置 SoftMaxHeapSize**：软上限让 JVM 在不影响延迟的前提下尽量少用内存，云环境节省成本
3. **ConcGCThreads 不要设太大**：默认值通常是 CPU 核数的 12.5%~25%，过大会抢占应用线程 CPU
4. **ZGC 不需要 MaxGCPauseMillis**：STW 已经是亚毫秒级，不可配置

### 6.4 Shenandoah 配置

**适用场景**：低延迟（< 20ms）、Red Hat 生态、中等堆大小。

```bash
java -XX:+UseShenandoahGC \
     -XX:ShenandoahGCHeuristics=adaptive \  # 自适应启发式（推荐）
     -Xms4g -Xmx4g \
     -jar app.jar
```

| 启发式策略 | 说明 |
|-----------|------|
| `adaptive` | 自适应，根据 GC 频率和效率动态调整（推荐） |
| `static` | 固定阈值触发 |
| `compact` | 更积极地压缩，减少碎片 |
| `aggressive` | 最积极回收，延迟最优但吞吐量最低 |

### 6.5 如何选择 GC 收集器：决策树

```
                      你的应用场景？
                           │
              ┌────────────┼────────────┐
              │            │            │
          批处理/计算    通用业务     低延迟/实时
          吞吐量优先     均衡考量     延迟敏感
              │            │            │
              ▼            ▼            ▼
         Parallel GC     G1 GC      堆大小？
                                      │
                              ┌───────┼───────┐
                              │               │
                          ≤ 32GB          > 32GB
                              │               │
                              ▼               ▼
                           G1 GC           ZGC
                        (停顿目标         (分代 ZGC
                         可放宽)          JDK 21+)
```

**快速决策表**：

| 场景 | 推荐 GC | 理由 |
|------|---------|------|
| Spring Boot 微服务（堆 2-8GB） | G1 | 通用均衡，默认即可 |
| 实时交易系统（延迟 < 10ms） | ZGC (分代) | 亚毫秒 STW |
| 数据分析 / ETL（吞吐量优先） | Parallel | 最大化吞吐 |
| API 网关（低延迟 + 高并发） | ZGC (分代) | 延迟稳定 |
| 大内存缓存服务（堆 32GB+） | ZGC (分代) | 支持超大堆 |
| IoT / 嵌入式（小堆 < 512MB） | Serial / G1 | 小堆 G1 也可胜任 |
| Kafka / 消息队列 | G1 | 吞吐与延迟均衡 |

### 6.6 Spring Boot / 云原生场景 GC 配置示例

**Spring Boot + G1（最常见）**：

```bash
# application.yml 中的 JAVA_OPTS
java -XX:+UseG1GC \
     -Xms2g -Xmx2g \
     -XX:MaxGCPauseMillis=100 \
     -XX:+ParallelRefProcEnabled \
     -XX:+UseStringDeduplication \       # G1 字符串去重，减少重复 String 对象
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \  # GC 日志
     -XX:+HeapDumpOnOutOfMemoryError \   # OOM 时自动 dump
     -XX:HeapDumpPath=/tmp/heapdump.hprof \
     -jar app.jar
```

**Spring Boot + ZGC（低延迟）**：

```bash
java -XX:+UseZGC \
     -XX:+ZGenerational \
     -Xms4g -Xmx4g \
     -XX:SoftMaxHeapSize=3g \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/tmp/heapdump.hprof \
     -jar app.jar
```

### 6.7 容器环境（Docker / K8s）GC 注意事项

**1. 容器感知（JDK 10+ 已支持，JDK 17 完善）**

```dockerfile
# Dockerfile
FROM eclipse-temurin:17-jre
# JDK 10+ 自动感知 cgroup 内存限制，-Xmx 不设则默认为容器内存的 1/4
# 显式设置更可控：
ENV JAVA_OPTS="-XX:+UseG1GC -Xms512m -Xmx512m"
```

**2. K8s 资源限制与 GC 配置对齐**

```yaml
# deployment.yaml
resources:
  requests:
    memory: "1Gi"
  limits:
    memory: "2Gi"
```

```bash
# -Xmx 必须小于 limits.memory，留空间给 Metaspace / 栈 / 堆外内存
# 推荐：-Xmx = limits.memory × 70%~80%
java -XX:+UseG1GC -Xms1536m -Xmx1536m -jar app.jar
```

**3. 容器 OOMKilled 排查**

| 现象 | 原因 | 解决 |
|------|------|------|
| JVM 堆未 OOM 但容器被 Kill | Metaspace / 线程栈 / 堆外内存超出容器限制 | 增大容器内存或减小 -Xmx |
| GC 正常但 RSS 持续增长 | Native 内存泄漏（DirectByteBuffer / JNI） | 用 `jcmd <pid> VM.native_memory` 排查 |

---

## 七、GC 日志与监控

### 7.1 JDK 9+ 统一日志格式（-Xlog）

JDK 9 引入统一日志框架（Unified Logging），替代了旧的 `-XX:+PrintGCDetails` 等参数。

```bash
# 基础 GC 日志
-Xlog:gc*:file=gc.log:time,uptime,level,tags

# 只看停顿信息
-Xlog:gc+phases=info

# 详细的 GC 原因
-Xlog:gc+cause:info

# 完整诊断日志（调试用）
-Xlog:gc*=debug:file=gc.log:time,uptime,level,tags
```

**日志格式示例（G1）**：

```
[2026-06-04T10:30:15.123+0800][info][gc] GC pause (G1 Evacuation Pause) (young) 256M->128M(512M) 12.345ms
[2026-06-04T10:30:16.456+0800][info][gc] GC pause (G1 Evacuation Pause) (mixed) 384M->256M(512M) 45.678ms
```

**解读**：`GC前使用量 -> GC后使用量(总容量) 停顿时间`

### 7.2 关键指标

| 指标 | 含义 | 健康标准 |
|------|------|---------|
| **吞吐量** | 应用运行时间 / 总时间 | > 95%（G1），> 90%（ZGC） |
| **停顿时间** | 单次 GC STW 时长 | G1: < 200ms, ZGC: < 1ms |
| **GC 频率** | 单位时间 GC 次数 | Young GC: 几秒一次, Full GC: 几小时一次 |
| **晋升速率** | 单位时间对象晋升到老年代的量 | 监控趋势，突然升高需排查 |
| **GC 占比** | GC 时间 / 总时间 | < 5% |

### 7.3 常用监控工具

| 工具 | 用途 | 命令 |
|------|------|------|
| `jstat` | 实时查看 GC 统计 | `jstat -gcutil <pid> 1000` |
| `jmap` | 堆直方图 / heap dump | `jmap -histo:live <pid>` |
| `jcmd` | 多功能诊断（推荐） | `jcmd <pid> GC.heap_info` |
| `jinfo` | 查看运行时 JVM 参数 | `jinfo -flags <pid>` |
| VisualVM | GUI 分析 | 连接 JMX 或打开 dump |
| GCViewer | GC 日志可视化 | 分析 gc.log |
| GCEasy | 在线 GC 日志分析 | 上传 gc.log 到 gceasy.io |

**jstat 输出解读**：

```bash
$ jstat -gcutil 12345 1000
  S0     S1     E      O      M     CCS    YGC   YGCT   FGC  FGCT   GCT
  0.00  45.23  67.89  34.56  92.34  89.12   127  1.234    2  0.456  1.690
  │      │      │      │      │      │       │     │      │     │      │
  │      │      │      │      │      │       │     │      │     │      └─ 总 GC 时间(s)
  │      │      │      │      │      │       │     │      │     └─ Full GC 时间(s)
  │      │      │      │      │      │       │     │      └─ Full GC 次数
  │      │      │      │      │      │       │     └─ Young GC 时间(s)
  │      │      │      │      │      └─ CCS 使用率(%)       │
  │      │      │      │      └─ Metaspace 使用率(%)       │
  │      │      │      └─ Old 使用率(%)                     │
  │      │      └─ Eden 使用率(%)                           │
  │      └─ Survivor1 使用率(%)                             │
  └─ Survivor0 使用率(%)
```

### 7.4 GC 日志分析实战

**Step 1：确认 GC 收集器和堆配置**

```bash
jcmd <pid> VM.flags | grep -E "Use.*GC|HeapSize|MaxGCPause"
```

**Step 2：观察 GC 趋势**

```bash
# 每 1 秒采样一次，观察 30 次
jstat -gcutil <pid> 1000 30
```

**Step 3：定位长停顿**

```bash
# 从日志中筛选停顿 > 100ms 的 GC
grep "ms$" gc.log | awk -F'[()]' '{print $0}' | ...
# 或用 GCEasy 在线分析
```

**Step 4：确认是否频繁 Full GC**

```bash
# 统计 Full GC 次数
jstat -gcutil <pid> | awk '{print $9}' | tail -1
# FGC 列 > 0 且持续增长 → 需要排查
```

---

## 八、GC 调优实战

### 8.1 调优目标与权衡

```
         吞吐量
           ▲
           │
     P     │           不可能三角
     a     │         ┌──────────┐
     r     │        /  调优就是  \
     a     │       /   找到平衡点  \
     l     │      /     而非最优    \
     l     │     └──────────────┘
     e     │
     l     │
           └──────────────────────────▶ 延迟（低停顿）
          /
         /
        ▼
     内存使用量
```

| 优化目标 | 调优方向 | 适合收集器 |
|---------|---------|-----------|
| 最大吞吐量 | 增大堆、减少 GC 频率 | Parallel、G1 |
| 最低延迟 | 缩短 STW、并发回收 | ZGC、Shenandoah |
| 最小内存 | 缩小堆、频繁 GC | Serial、G1 |

### 8.2 常见 GC 问题与排查

#### 问题 1：Full GC 频繁

```
排查流程：
  │
  ├─ jstat 确认 Full GC 频率和触发原因
  │
  ├─ 原因 A：老年代空间不足
  │   ├─ jmap -histo:live 查看大对象分布
  │   ├─ 排查内存泄漏（静态集合、ThreadLocal、未关闭资源）
  │   └─ 考虑增大老年代或整个堆
  │
  ├─ 原因 B：Metaspace 不足
  │   ├─ -XX:MaxMetaspaceSize 是否太小
  │   ├─ 排查动态类生成（CGLIB、Groovy）
  │   └─ 适当增大 Metaspace
  │
  ├─ 原因 C：System.gc() 被调用
  │   ├─ 检查代码或 NIO ByteBuffer 分配触发
  │   └─ -XX:+DisableExplicitGC
  │
  └─ 原因 D：晋升失败
      ├─ Survivor 区太小，对象过早晋升
      ├─ 调整 -XX:SurvivorRatio
      └─ 增大新生代或调整 MaxTenuringThreshold
```

#### 问题 2：停顿时间过长

| 收集器 | 可能原因 | 解决方案 |
|--------|---------|---------|
| G1 | 存活对象过多，复制耗时长 | 增大堆、调整 Region 大小、减少 MaxGCPauseMillis |
| G1 | Mixed GC 退化为 Full GC | 提前触发并发标记（降低 IHOP）、增大堆 |
| ZGC | 分配停顿（非 STW） | 增大 ZAllocationSpikeTolerance、用分代模式 |
| 通用 | Reference 处理耗时长 | `-XX:+ParallelRefProcEnabled` |

#### 问题 3：内存泄漏

```bash
# 1. OOM 时自动 dump
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/dump.hprof

# 2. 手动 dump
jcmd <pid> GC.heap_dump /tmp/dump.hprof

# 3. 用 MAT 分析
#    - Dominator Tree：找最大对象
#    - Leak Suspects：自动检测泄漏
#    - GC Root 引用链：确认对象为何未被回收
```

**常见泄漏模式**：

| 模式 | 代码示例 | 修复 |
|------|---------|------|
| 静态集合 | `static List<Object> cache = new ArrayList<>();` 持续 add 不 remove | 用 WeakHashMap / 设置容量上限 / 定期清理 |
| ThreadLocal | `threadLocal.set(obj)` 后未 remove | try-finally 中 remove |
| 未关闭资源 | 打开 InputStream 未 close | try-with-resources |
| 监听器未注销 | `eventBus.register(listener)` 后未 unregister | 生命周期结束时注销 |
| 缓存无淘汰 | `Map<K,V> cache` 无大小限制 | 用 Caffeine / Guava Cache 设置淘汰策略 |

#### 问题 4：晋升失败 / 空间分配担保失败

```
晋升失败（Promotion Failure）：
  Minor GC 时存活对象需要复制到 Survivor/老年代，
  但目标区域空间不足

空间分配担保失败：
  Minor GC 前，JVM 检查老年代可用连续空间
  < 历次晋升平均大小，且不允许担保失败

排查：
  -XX:+PrintGCDetails 中观察 "promotion failed" 字样
  增大 Survivor 区：-XX:SurvivorRatio=6（默认 8，值越小 Survivor 越大）
  增大老年代预留：-XX:G1ReservePercent=15
```

### 8.3 G1 调优实战

**案例：Spring Boot 服务，堆 4GB，G1 偶现 500ms+ 停顿**

```
Step 1：分析日志
  - Young GC 平均 30ms ✅
  - Mixed GC 偶尔 500ms+ ❌
  - Full GC 每天出现 2-3 次 ❌

Step 2：定位原因
  - 混合回收退化为 Full GC
  - 老年代对象增长过快，Mixed GC 来不及回收

Step 3：调整
  -XX:InitiatingHeapOccupancyPercent=35   # 45→35，更早触发并发标记
  -XX:G1MixedGCCountTarget=16             # 8→16，分更多轮次回收
  -XX:G1MixedGCLiveThresholdPercent=75    # 85→75，回收更多 Region
  -XX:G1ReservePercent=15                 # 10→15，更多预留空间
  -XX:+ParallelRefProcEnabled             # 并行处理 Reference

Step 4：验证
  - Full GC 消除 ✅
  - Mixed GC 最大停顿 120ms ✅
  - Young GC 仍 30ms ✅
```

### 8.4 ZGC 调优实战

**案例：交易服务，堆 16GB，ZGC 分配停顿导致 P99 延迟上升**

```
Step 1：分析
  - STW 停顿 < 1ms ✅
  - 但分配停顿（Allocation Stall）偶尔 50ms+ ❌
  - 原因：突发流量导致对象分配速度超过 ZGC 回收速度

Step 2：调整
  -XX:ZAllocationSpikeTolerance=4         # 2→4，预留更多空闲页面
  -XX:ConcGCThreads=4                     # 2→4，加快并发回收
  -XX:+ZGenerational                      # 启用分代模式（JDK 21+）
  -XX:SoftMaxHeapSize=12g                 # 16g 堆设 12g 软上限

Step 3：验证
  - 分配停顿消除 ✅
  - P99 延迟从 80ms 降至 15ms ✅
  - GC 占 CPU 从 3% 升至 5%（可接受）
```

---

## 九、面试高频问题

### Q1: GC 的基本流程是什么？

> JVM 通过可达性分析判断对象是否存活：从 GC Roots 出发沿引用链遍历，不可达的对象为垃圾。现代收集器使用三色标记法进行并发标记，通过写屏障（G1/CMS）或读屏障（ZGC/Shenandoah）保证标记正确性。回收阶段，新生代用复制算法（对象存活率低），老年代用标记-整理（对象存活率高）。

### Q2: G1 和 CMS 的核心区别？

| 维度 | CMS | G1 |
|------|-----|-----|
| 堆布局 | 物理分代（连续） | Region 化（逻辑分代） |
| 老年代算法 | 标记-清除（碎片化） | 复制（Region 间复制，无碎片） |
| 停顿预测 | 不可控 | MaxGCPauseMillis 可预测 |
| 碎片问题 | 严重 | 无（Region 复制天然压缩） |
| 浮动垃圾 | 有 | 有（SATB 导致，比 CMS 略多） |
| Full GC | 退化 Serial Old | 退化 Serial（JDK 10 前）/ 并行（JDK 10+） |
| 漏标处理 | 增量更新 | SATB |

### Q3: ZGC 为什么能做到亚毫秒级 STW？

> 三点核心：**染色指针**、**读屏障自愈**、**并发转移**。
>
> 1. 染色指针在 64 位指针中嵌入 GC 标记位，不需要修改对象头，标记操作只需修改指针视图
> 2. 读屏障在每次引用读取时检查指针颜色，如果对象已被移动则自动修正引用（自愈），应用线程和 GC 线程可以并发移动对象
> 3. 所有 STW 阶段只做少量固定工作（标记 GC Roots、转移 Roots 直接引用），工作量与堆大小无关，所以 STW 时间恒定 < 1ms

### Q4: 什么是记忆集（Remembered Set）？为什么需要？

> 分代收集中，新生代 GC 只回收新生代，但老年代可能持有新生代对象的引用。如果不扫描老年代，可能误回收被老年代引用的新生代对象。
>
> 记忆集记录了"老年代 → 新生代"的跨代引用，让新生代 GC 只扫描 RSet 中的引用，无需遍历整个老年代。
>
> G1 中每个 Region 都有自己的 RSet，记录其他 Region 对本 Region 的引用。RSet 的维护通过写屏障实现：引用写入时，如果跨 Region，在目标 Region 的 RSet 中添加记录。
>
> ZGC 分代模式也使用 RSet 记录老年代→年轻代的跨代引用。

### Q5: 什么时候对象会进入老年代？

> 四种情况：
> 1. **年龄达到阈值**：对象在 Survivor 区每经历一次 Minor GC 年龄 +1，达到 `-XX:MaxTenuringThreshold`（默认 15）晋升
> 2. **动态年龄判断**：Survivor 区中某年龄及以上的对象总大小超过 Survivor 空间的 50%，该年龄及以上对象全部晋升
> 3. **大对象直接分配**：G1 中超过 Region 容量 50% 的对象直接分配为 Humongous Region
> 4. **Survivor 空间不足**：Minor GC 后 Survivor 放不下，多余对象通过担保机制进入老年代

### Q6: 如何排查线上 Full GC 频繁的问题？

> 1. `jstat -gcutil <pid> 1000` 确认 Full GC 频率和堆使用率
> 2. 查 GC 日志确认 Full GC 触发原因（老年代满 / Metaspace 满 / System.gc() / 晋升失败）
> 3. 根据原因分别排查：
>    - 老年代满 → dump 分析大对象和引用链，排查内存泄漏
>    - Metaspace 满 → 排查动态类生成，适当增大 Metaspace
>    - System.gc() → 加 `-XX:+DisableExplicitGC`
>    - 晋升失败 → 调整新生代比例或增大堆
> 4. 确认 GC 参数是否合理（堆大小、IHOP、SurvivorRatio 等）

### Q7: JDK 17+ 生产环境应该选什么 GC？

> **大多数场景选 G1**：JDK 17 默认 GC，通用均衡，开箱即用，堆 32GB 以内停顿可控在 100-200ms。
>
> **低延迟场景选 ZGC**：实时交易、API 网关、微服务等对延迟敏感的场景。JDK 21+ 推荐 `-XX:+UseZGC -XX:+ZGenerational` 分代模式，STW < 1ms，支持 TB 级堆。
>
> **吞吐量优先选 Parallel**：批处理、ETL、科学计算等离线场景。
>
> 选型核心依据是业务对延迟的容忍度，而非堆大小。堆 8GB 的实时服务也可能需要 ZGC，堆 64GB 的批处理用 G1 或 Parallel 就够了。

### Q8: 分代 ZGC（JDK 21）相比非分代 ZGC 有什么提升？

> 非分代 ZGC 每次回收扫描全堆，对年轻代大量朝生夕死对象的回收效率不高——全堆扫描成本高，但收益（回收比例）低。
>
> 分代 ZGC 将堆分为年轻代和老年代：
> - 年轻代频繁回收（利用"绝大多数对象朝生夕死"的弱代假说），每次只扫描年轻代，效率高
> - 老年代按需回收，频率远低于年轻代
> - 通过记忆集（RSet）记录跨代引用，年轻代 GC 不需要扫描老年代
>
> 实测吞吐量提升 10%~30%，分配停顿显著减少。JDK 21+ 强烈推荐使用分代模式。
