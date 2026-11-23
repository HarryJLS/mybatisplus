# 性能设计与测量

历史样例测量日期：2026-11-15；0.5.2 的本次对照见下文。环境：macOS arm64，本机 Rust 1.93.0，release 构建（`opt-level=3`、thin LTO），Java/JNI 使用 IDEA 自带 JBR 25。

## 已测量的解析样例

| 项目 | 样例 / 次数 | 本机结果 |
| --- | --- | --- |
| Rust DDL 解析 | 4 列 CREATE TABLE，100,000 次 | 约 1.0–1.2 微秒/次 |
| Rust 日志还原 | 2 个参数的 Preparing/Parameters，100,000 次 | 约 0.6–0.7 微秒/次 |
| Java → JNI → Rust → JSON 返回 | 同类日志，预热 2,000 次后运行 20,000 次 | 约 2.6–2.9 微秒/次 |
| 独立原生基准程序峰值 RSS | 包括上述 200,000 次解析 | 2,260,992 字节，约 2.16 MiB |
| 独立原生程序峰值 memory footprint | macOS `/usr/bin/time -l` | 1,442,200 字节，约 1.38 MiB |
| 原生动态库文件 | macOS arm64 release | 约 425 KiB |
| 本地 SDK 构建的插件 ZIP | 0.2.0，Java 插件 + Rust + MyBatis/Gson | 约 2.13 MiB |
| 本地 SDK 构建的插件 ZIP | 0.3.0，增加独立 iBATIS 求值器，不打包旧运行时 | 2,242,306 字节，约 2.14 MiB |

这些是**小型样例与独立引擎的测量**。它们不代表 IDEA 整体内存、完整插件常驻内存、复杂 SQL 的耗时或与原插件的公平性能对比。尚未完成长时间真实项目使用的 CPU/堆内存对照测试，不能据此宣称“IDEA 只占 2 MB”或“插件 CPU 恒为 0”。

## 减少常驻开销的实现

- **启动**：没有启动扫描活动、定时器、后台轮询、自建线程或外部常驻进程。
- **原生引擎**：第一次使用文本工具时才加载；补全、导航和打开工具窗口均不依赖 JNI 调用。
- **索引**：IDEA 的 FileBasedIndex 维护 namespace → 文件关系。MyBatis XML 在根标签属性读取完成后停止；iBATIS sqlMap 会继续流式读取该文件的语句 id，以支持旧式裸 id 调用。只处理平台判定发生变化的文件，不在补全时遍历整个项目。
- **iBATIS 实现查找**：仅在明确点击导航后使用 Java 覆盖方法索引；最多 128 个实现、8 层同类辅助方法，不在每次编辑或行标记计算时扫描实现类。
- **缓存**：namespace 查找结果附着于对应 Java 类；XML 元数据附着于 XML 根节点；参数绑定按方法共享。普通 XML 节点不会重复持有一整份参数表，局部变量存在时才创建作用域模型。
- **缓存生命周期**：使用 IDEA CachedValues 和 PSI/索引修改追踪；没有保存全项目 PSI 的静态集合。缓存计算闭包不持有另一份 Java PSI。
- **热点表达式**：参数占位符、属性分段和索引匹配的正则预编译。
- **耗时操作**：CRUD 生成、日志还原、SQL 预览由用户操作触发，在 IDEA 后台任务中执行；完成后更新界面。
- **依赖**：不打包 JDBC 驱动、HTTP 客户端、Kotlin、完整 SQL 解析与模板引擎；动态 SQL 使用一份 MyBatis 运行时。
- **输入边界**：Rust 请求最大 8 MiB；Java 文本入口限制字符数；单表字段最多 2,000；SQL 预览跨 Mapper 引用最多 64 个。
- **旧动态 SQL**：0.3.0 的 iBATIS 求值器仅在预览时创建请求状态，不反射/实例化业务类；64 层展开、10000 次单集合迭代、10000 参数、100000 步及 4 MiB 输出上限，防止意外循环和组合展开耗尽资源。旧运行时仅为测试依赖，不进入发布包。
- **0.4.0 类型别名**：变化的 XML/properties/YAML 由持久索引提取常量配置；按模块范围读取最多 128 个配置文件，并仅在配置包范围内解析最多 256 个包/4096 个类。结果为字符串，按 XML 文件共享缓存；参数声明使用 SmartPsiElementPointer，不保存跨文件 PSI 的静态集合。
- **0.4.0 编辑检查**：OGNL 输入最多 16 KiB、64 层、2048 个语法节点，只解析不求值。SELECT 最多 256 KiB，include 展开最多 32 层/2048 步；按语句/resultMap 共享输出列缓存。无法完整解析的投影不执行缺失列判断。
- **0.4.0 注解和控制台**：注解常量数组最多 1024 段、合并内容最多 4 MiB。控制台配对状态附着于该控制台，最多 64 组，每行最多 16 KiB；点击还原链接才执行后台解析，没有额外常驻线程或定时监听。

MyBatis 自身包含 OGNL 表达式缓存；复杂表达式和长期编辑仍应在真实项目中测量。任务取消在操作开始/结束检查，正在执行的单次 JNI 或 MyBatis 求值不能即时中断。原生请求数据按调用释放，已加载的库映射会保留到插件类加载器/JVM 生命周期结束，因此插件要求重启完成安装/卸载。

## 重复验证

```sh
python3 scripts/build.py --test --bench
/usr/bin/time -l native/target/release/mybatis-engine --bench
python3 scripts/test_ide.py
python3 scripts/test_legacy.py
```

原始输出位于 `build/verification.log`、`build/native-benchmark.txt`、`build/native-memory.txt` 和 `build/ide-verification.log`。IDEA 测试使用本地隔离配置，不改变正在运行的 IDEA 配置。

0.3.0 于 2026-11-16 完成 71 组真实 iBATIS 2 SQL/参数对照与 38 项独立求值断言，记录在 `build/legacy-oracle.log` 与 `build/verification.log`。这些验证语义和展开上限，不是旧求值器的长期 CPU/内存测量；不据此扩大前述独立 Rust 基准的性能结论。

0.4.0 于 2026-11-17 的本地 SDK 安装包为 2,301,666 字节（约 2.20 MiB）。新增编辑能力通过 24 项核心断言及共 51 项 IDEA 集成测试；未重新测量长期真实项目 CPU/堆内存，也未把 SQL 语言注入范围测试当作 Database 连接或业务 SQL 验证。

## 仍需测量的真实使用场景

0.5.0 于 2026-11-21 补充运行接入。MCP 使用平台内置服务和线程池，最多 4 个并发请求；数据库连接由明确操作创建并关闭，不建立插件自己的连接池、定时扫描或监听线程。MyBatis 预览先检查 include/JSON/表达式与保守展开上界，JDBC 请求设置查询超时与结果上限。驱动内部行为、连接超时和长期实际项目资源占用未测量；测试通过不能扩大为完整 IDEA 的 CPU/内存结论。

验证记录：`build/verification-0.5.0.log`、`build/ide-verification-0.5.0.log`、`build/integration-verification-0.5.0.log`、`build/database-verification-0.5.0.log`、`build/legacy-oracle-0.5.0.log`。数据库断言使用临时 H2；Kotlin/Spring 专测按依赖加载本机 SDK 扩展，配置与用户正在使用的 IDEA 隔离。

1. 相同大型项目，分别启用/禁用插件，对比索引完成后的空闲 CPU 和编辑时 CPU。
2. 对同一 Mapper 进行长时间编辑和补全，用 IDEA 的内存快照检查 Java/Rust/OGNL 的保留对象。
3. 关闭项目和重新打开项目，检查缓存生命周期及跨项目引用。
4. 使用复杂动态 SQL、大量列和跨文件片段，记录耗时分布与最大输入峰值内存。

## 0.5.2 编辑缓存与原生分配优化

更新日期：2026-11-21；提交日期：2026-11-22。验证环境为本地 IDEA 2026.1.4（261.26222.65）、macOS arm64、JBR 25、Rust 1.93.0。仍仅声明 261.*；没有将最新版兼容或计划项写成已完成。

- 类型别名表存放在模块/项目的 CachedValue 中，仅保存字符串。同模块多个 XML 共享结果，依赖 Java/Kotlin 类型修改、配置索引、项目根和设置；不再直接依赖所有项目 PSI 修改。平台的索引重建或全局失效仍可触发重算。
- 属性表附着在对应 PsiClass，复用字段/getter 描述与声明智能指针。普通实体直接共享不可变表；泛型按本次类型替换并复用声明指针，不保存无界的泛型实例缓存。跨文件继承、Java/Kotlin 类型和项目根变化使其失效；无法解析的 List/Set/Collection 仍提供 size/isEmpty，数组提供 length。
- XML 标签没有受检查表达式、文本没有参数占位符时，不创建参数模型；引用解析与参数模型缓存计算减少重复的 Mapper 方法查找。OGNL 编辑语法树仅附着于属性 PSI；语义检查仍按当前参数/类型执行，编辑检查不求值。
- Mapper 根节点共享 resultMap → SELECT 的反向引用，避免每个 resultMap 遍历全部 SELECT。投影缓存记录实际访问的 XML 文件；列缓存传播 include 文件依赖，并在缺失 namespace 的索引变化后重试。相关 XML 修改仍会重算；无关 Java 修改不使纯 SQL 投影失效。展开前检查 256 KiB 输出边界，循环补充平台取消检查。
- Rust DDL 分段器返回借用切片的迭代器，常规无注释输入不再复制为清理字符串，不再复制全文生成小写版本；字段超限在逐段处理时退出。主键成员查询使用集合，日志输出直接写入一个字符串。JNI 协议、懒加载方式与独立 MyBatis/iBATIS 求值语义保持兼容，没有增加线程或后台轮询。

### 独立原生对照

基线为变更前真实 Git HEAD `aff83eeea43096cde38f8780f29f3fc19ae877b9`（0.5.1），在临时目录用相同 Cargo.lock 离线编译。两份引擎均为 release 构建；在没有同时执行构建或测试时交替运行 7 轮，每轮每项 100000 次。

| 样例 | 基线中位耗时 | 0.5.2 中位耗时 |
| --- | --- | --- |
| 4 列 CREATE TABLE | 0.785 微秒/次 | 0.652 微秒/次 |
| 2 参数 Preparing/Parameters | 0.509 微秒/次 | 0.490 微秒/次 |

这是直接调用原生解析器的小样例。0.5.2 本轮 Java → JNI → Rust → JSON 日志样例为 2.612 微秒/次（预热后 20000 次），没有对 Java/JNI 做相同进程的前后对照，不据此宣称端到端调用已有比例相同的提升。单次独立原生基准峰值 RSS 为 2408448 字节（约 2.30 MiB），也不代表插件或 IDEA 常驻内存。

新增 `scripts/native_allocations.rs` 使用独立可执行文件的计数分配器，统计解析期间 alloc/alloc_zeroed/realloc 的次数和累计请求字节；探针不进入插件或常规性能基准。4 列 DDL/2 参数日志各 1000 次，1000 列 DDL/100 组日志各 100 次；输入构造、打印和 JNI/JSON 不计入。

| 样例 | 基线分配/扩容次数 | 0.5.2 次数 | 基线累计请求字节 | 0.5.2 累计请求字节 |
| --- | --- | --- | --- | --- |
| 4 列 DDL | 23 | 15 | 801 | 325 |
| 1000 列 DDL | 4022 | 3010 | 253908 | 134248 |
| 2 参数日志 | 13 | 11 | 567 | 418 |
| 100 组日志 | 1107 | 908 | 53346 | 39460 |

累计请求字节包含扩容申请的完整新容量，**不是峰值内存或保留内存**。1000 列 DDL 该指标约减少 47%，100 组日志约减少 26%；不能据此宣称 IDEA 整体内存下降相同比例。

编译并运行当前版本的分配探针：

```sh
cargo build --release --locked --manifest-path native/Cargo.toml
rustc --edition=2021 scripts/native_allocations.rs --extern mybatis_engine=native/target/release/libmybatis_engine.rlib -L dependency=native/target/release/deps -o build/native-allocations
build/native-allocations
```

### 验证与保留限制

18 项 Rust 测试、152 项核心断言、69 项常规 IDEA 集成测试及 9 项实际可选扩展测试通过。新增测试验证同模块别名共享、1000 次属性读取共用同一表、普通 SQL 编辑后的缓存保留、不同泛型参数隔离、继承与别名修改失效、Kotlin 属性修改失效、跨文件 include 修改和缺失片段重试；不把缓存身份断言作为耗时或堆内存测量。安装包的 41 个注册实现/归档完整性和从 ZIP 懒加载 JNI 的日志/DDL 验证通过。

本次没有公开 API 可直接配置 MyBatis 内部 OgnlCache 的容量，未改写或反射清空其私有静态状态；预览表达式长期累积仍需单独设计兼容方案。也未执行大型真实项目启用/禁用插件的 CPU、堆保留和进程 RSS 对照，或项目反复关闭后的堆快照。上述样例与测试不能替代这些测量。

原始结果：`build/verification-0.5.2.log`、`build/ide-verification-0.5.2.log`、`build/integration-verification-0.5.2.log`、`build/native-comparison-0.5.2.json`、`build/native-allocations-0.5.2.txt`、`build/native-memory-0.5.2.txt` 与 `build/archive-verification.json`。

## IDEA 2026.3 现场采样与请求级预览缓存（0.5.3/0.5.4）

验证日期：2026-11-22（Asia/Shanghai）。本机 IDEA 2026.3，IU-263.7307.200，JBR 25.0.4.1，12 个逻辑 CPU。业务项目包含 9268 个 Java 文件；探针选择其中 80 个 Mapper XML，单文件最多检查 20 个语句，实际共 372 个方法。只读取当前已打开项目、生成离线 SQL；未连接数据库、未修改业务项目文件。

### 0.5.3 的实际 IDE 进程测量

通过 JFR 和外部有限时长采样测量，不向发布插件添加定时器、执行器或常驻监控线程。20 分钟采样属于观察窗口，不能代替数天使用或反复关闭项目的内存验证。

| 项目 | 结果与范围 |
| --- | --- |
| 完整 IDEA CPU，20 分钟、239 个 `ps` 样本 | 单核 100% 口径，中位 0.5%、平均 5.29%、峰值 212.1% |
| 完整 IDEA RSS，同一窗口 | 首次 5418696704 字节，最后 5444091904 字节，最大 5445337088 字节；首末差约 24.2 MiB |
| JFR CPU，978 个事件 | 按全机 CPU 能力归一化，中位 0.142%、平均 0.461%、最大 17.870%；与 `ps` 口径及采样时刻不同 |
| 缓存复测 | 两轮各 10000 次预览，每轮使用不同表达式；第二轮重复第一轮表达式集合。MyBatis 静态 expressionCache 两轮均为 0 → 0 |
| 探针后显式 GC / class histogram | 只剩 22 个 OGNL AST 对象，属于当前编辑检查等保留状态；未保留这 10000 组预览表达式。浅层对象计数不是完整插件保留内存 |

RSS 包含 IDEA、JVM、原生映射与其他已启用扩展；不能将其全部归因于本插件。初步 JFR 的 52 个 Java 执行样本没有本插件调用栈，不据此宣称插件 CPU 为零或证明所有瞬时峰值都来自其他扩展。进程中还有其他插件和平台后台活动。GC 前后堆数值变化包含全 IDE 的垃圾回收和软缓存回收，不能视为插件独占内存差值。

第一轮后端探针：80/80 个接口、372/372 个语句方法解析；79 个实际预览尝试中 45 个成功。每 XML 的批量模型解析中位 40.7 ms、P95 154.7 ms；成功预览中位 7.8 ms、P95 176.1 ms。模型数字包含该文件多个语句和冷解析成本，不是单次补全耗时。

第二轮补齐缺少的空集合参数：62/79 个实际预览成功，模型批次中位 19.9 ms、P95 104.0 ms，成功预览中位 3.8 ms、P95 28.3 ms。两轮参数和成功 SQL 集合不同，不能据此计算同一 SQL 的性能提升比例。剩余 17 项中，11 项是明确不求值的业务类方法、2 项需要进一步补齐 JSON 集合参数，4 项为同 namespace 分文件片段未收集的问题。

### 已落地的修复与边界

- 0.5.3：基于真实 MyBatis 3.5.19 SQL 节点与 OGNL，表达式 AST 缓存由一次渲染拥有，最多 256 条/256 Ki 字符；语法树检查仍限定每条表达式 2048 节点、64 层、16 Ki 字符。if/bind/foreach/动态文本不再将用户表达式写入 MyBatis 私有静态缓存，复用请求内解析结果，渲染后释放。foreach 参数改写和前缀规则从 Apache-2.0 的 MyBatis 3.5.19 实现派生，并用真实引擎做对照，不使用替换表达式冒充兼容。
- 0.5.3：修复 XML 声明值被当成普通属性造成的 ClassCastException；带声明、DTD 的高亮与声明内补全已加入回归测试。
- 0.5.4：修复同 namespace 多 XML 的本地 SQL 片段解析；快照只沿当前语句实际需要的 include 收集文件，按文件去重、按请求共享 namespace 索引查询。MyBatis 的嵌套裸 refid 按调用方 namespace 求解；保留 64 个关联文件、总输入、展开深度和步骤上限。GUI、数据库入口及 MCP 共用这一流程。
- 构建：263 的 SDK 类使用 Java 25，旧 JDK 21 编译器无法读取。构建工具更新为 IntelliJ Platform Gradle 2.20.1 / Gradle 9.2.1，JDK 25 编译、`--release 21` 输出；SDK 模块 JAR 仅为 compile-only，不进入插件安装包。测试依赖、应用信息与配置按 SDK 版本隔离。

0.5.4 的 18 项 Rust 测试、171 项核心断言、74 项 IDEA 集成测试、9 项实际可选扩展测试、标准 Gradle `coreTest buildPlugin` 和归档检查通过。核心测试覆盖 2000 次不同表达式预览的静态缓存不增长，新增 IDEA 测试覆盖同 namespace 嵌套 include、忽略未使用片段及调用方 namespace 语义。修复包已通过 IDEA 官方 StartupActionScriptManager 加入启动更新队列，保留旧插件备份，避免运行中覆盖 JAR；修复包随后已由重启日志确认加载，最新复测见下节。

自动窗口控制一度返回 `cgWindowNotFound`；现场探针使用一次性、测试专用 JVM agent 在真正的 IDEA 进程里调用后端 PSI/预览 API，不做字节码改写、不执行界面动作、不创建监听器或常驻线程。界面点击、长期项目关闭/重新打开、生产数据库及云端四平台安装包不作为已完成项。

原始结果（本机生成目录，不进入发布包）：`build/ide-baseline-263.jfr`、`build/ide-runtime-263.jfr`、`build/ide-runtime-263.json`、`build/ide-runtime-263.summary.json`、`build/ide-probe-263.jfr`、`build/runtime-probe-263.json`、`build/runtime-probe-warm-263.json`、`build/ide-after-probe-histogram-263.txt`、`build/gradle-build-0.5.4.log`、`build/ide-verification-0.5.4.log`、`build/integration-verification-0.5.4.log`、`build/staged-install-0.5.4.json`。

### 0.5.4 重启后现场复测

2026-11-22，IDEA 的启动日志确认加载 0.5.4；临时界面探针已从插件目录移除。仍使用同一已打开业务项目及同一组 80 个 Mapper XML，通过一次性后端 agent 调用真正的插件代码。补齐前两轮明确缺少的集合参数后，80 个 Mapper 接口、372 个方法解析全部成功；79 个实际预览中 68 个成功，剩余 11 个仅为未支持、明确拒绝求值的业务方法。之前 4 个同 namespace 分文件的共享 SQL 片段失败案例全部通过，未再出现片段未收集错误。

每 XML 批量模型检查中位 20.9 ms、P95 90.8 ms、最大 238.6 ms；成功预览中位 9.7 ms、P95 37.7 ms、最大 170.4 ms。模型数字包含该文件多个语句；重启、参数和成功 SQL 集合变化使其不能直接作为前后性能提升比例。

又运行了 10000 次不同表达式的安全预览，MyBatis 静态缓存为 0 → 0。显式 GC 后的 histogram 中，OGNL AST 对象为 22 个、浅层合计 1024 字节，与 0.5.3 同样检查一致；这些是当前编辑状态等保留对象，不是插件完整占用，也未保留这 10000 组预览 AST。真实 IDE 内的原生验证显示：日志/DDL 工具首次调用前 NativeBridge 未加载，调用后加载成功，日志还原及 2 列 DDL 结果正确；普通导航和上述 SQL 预览没有提前加载 Rust。

| 完整 IDEA 进程观测 | CPU（单核 100% 口径） | RSS |
| --- | --- | --- |
| 5 分钟、60 个样本，包含冷解析与 10000 次压力预览 | 中位 1.3%、平均 29.91%、峰值 272.7% | 首次 4381868032 字节，最后 4800970752 字节 |
| 随后显式 GC 后的 2 分钟、24 个样本 | 中位 0.35%、平均 8.45%、峰值 185.0% | 首次 4811571200 字节，最后 4811472896 字节，最大 4811571200 字节 |

后一窗口 RSS 基本稳定；CPU 仍有短时峰值，不能只报告低中位数而省略平均/最大值。5 分钟 JFR 的 304 个 Java 执行样本中，43 个包含插件调用栈，主要出现在本次预览压力测试的解析、安全检查及 include 处理中。测量包括整个 JVM、IDE 和其他扩展，不能将全部峰值或 RSS 差值归为插件独占开销。最新启动之后未发现 MyBatis 插件异常栈或 blame 日志；仍有 Air、Rainbow Brackets 等其他扩展错误，不将它们作为本插件的修复或验证结果。

本次包含 0.5.3 的 20 分钟观察、0.5.4 的 5 分钟压力观察及 2 分钟 GC 后观察，属于有限窗口；没有完成数天真实使用、反复关闭/打开项目的堆保留对照或严格的启用/禁用 A/B 实验。生产数据库与不可离线求值的业务方法仍未验证。窗口控制不可用期间不声称已自动完成视觉点击检查；已完成 74 项 IDEA 集成测试、9 项可选扩展测试和上述真实进程后端调用验证。

新增本机原始记录：`build/runtime-probe-0.5.4.json`、`build/runtime-native-0.5.4.json`、`build/ide-final-0.5.4.jfr`、`build/ide-final-0.5.4.json`、`build/ide-final-0.5.4.summary.json`、`build/ide-poststress-0.5.4.json`、`build/ide-poststress-0.5.4.summary.json`、`build/ide-final-histogram-0.5.4.txt`。

## 0.5.7 SQL 编辑与映射索引

新增 MapperUsageIndex 仅在 XML 文件变化时流式收集 resultMap/extends/refid 字符串键；补全、列检查和片段上下文按引用键定位文件，不扫描项目。动态 SQL 上下文缓存附着于当前 XmlText，返回内容只有字符串/区间，依赖实际读取文件及索引变化；每个文本位置只创建一个 SQL 注入文件，避免按每个绑定参数重复复制整条 SQL。展开、文件、属性和输出上限见运行文档。已有性能集成测试会在本版验证中运行；未将本次功能测试扩大宣称为长期压力或生产项目性能测量。
