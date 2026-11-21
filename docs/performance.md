# 性能设计与测量

测量日期：2026-11-15。环境：macOS arm64，本机 Rust 1.93.0，release 构建（`opt-level=3`、thin LTO），Java/JNI 使用 IDEA 自带 JBR 25。

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
