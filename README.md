# MyBatis Assistant（Rust）

独立实现的 IntelliJ IDEA 插件，辅助 MyBatis 3 / iBATIS 2 开发。当前开发版本 **0.5.8**（已提交、未发布）。

Java 接入 IDEA 的语法树、索引和界面；Rust JNI 按需处理 DDL 与日志；SQL 预览根据 XML 根元素分派到 MyBatis 引擎或独立 iBATIS 求值器。原生库在第一次使用文本工具时加载，不创建常驻外部进程。

兼容声明为 **IDEA 2026.1–2026.3（261–263.*）**，本次完整验证 IDEA 2026.3（263.7307.200）；插件字节码目标为 Java 21。IDEA 2026.3 自带 JBR 25，读取其 SDK API 需要 JDK 25 编译器。自动构建发布一个统一插件 ZIP，Windows x86_64、macOS Apple Silicon / Intel 和 Linux x86_64 共用同一文件；插件按当前系统自动选择内置原生库。项目架构和协作要求见 [AGENTS.md](AGENTS.md)。

## 安装与入口

IDEA → Settings → Plugins → 齿轮 → Install Plugin from Disk，选择：

[GitHub Releases 安装包](https://github.com/HarryJLS/mybatisplus/releases/latest)。当前已发布版本为 **`mybatis-assistant-0.5.1.zip`**，Windows 和 Mac 使用同一个文件，无需解压。0.5.8 尚未发布；本次 macOS Apple Silicon 本地构建为 `build/distributions/mybatis-assistant-0.5.8-macos-aarch64.zip`，只包含本机原生库。GitHub 自动附加的 `Source code (zip)` 是源码包，不能直接安装。

Release 中的 `SHA256SUMS.txt` 与 `verification.json` 提供完整性校验和归档检查结果。支持上述四个系统/处理器组合；Windows ARM64、Linux ARM64 等组合尚无内置原生库。

重启后从 Tools → MyBatis Assistant 打开工具。对比体验时可暂时禁用原插件，避免显示两套提示。

## 当前功能

| 功能 | 说明 |
| --- | --- |
| 接口 / XML 导航 | MyBatis mapper、iBATIS sqlMap；namespace 全名、唯一简称、显式映射、无 namespace 文件名 |
| Mapper 鱼形图标 | 项目树与编辑器标签的 Mapper XML、Java/Kotlin Mapper 接口；接口名、方法名、XML namespace / 语句 id 旁的鱼形图标可点击跳转 |
| iBATIS DAO 导航 | 用户点击后查找实现、同类辅助方法、静态 SQL id 和常量拼接 |
| SQL API 引用 | SqlMapClient、Spring SqlMapClientTemplate、SqlSession 的完整语句字符串 |
| 参数与映射补全 | @Param、集合、索引、嵌套属性；resultMap、refid；旧 #property# / $property$ 与动态 property |
| 基础检查 | 重复 id、缺失 MyBatis 方法/语句、无效引用/属性/参数；iBATIS 不套用 MyBatis 代理约束 |
| 类型别名 | 内置别名、配置 XML、常量 properties/YAML 包名、包内 @Alias；模块范围索引查询，歧义不自动选类 |
| OGNL 编辑 | 语法、属性、方法数量/已知参数类型检查；泛型返回值链、Map 索引、foreach、顺序 bind；编辑检查不求值 |
| resultMap 列检查 | 静态 SELECT 字段/别名、include 展开、重复列、内联嵌套 columnPrefix；动态投影与通配符跳过缺失列判断 |
| 参数和映射重构 | @Param 绑定名、sql/resultMap/parameterMap id、foreach item/index、bind name；保持 namespace 前缀与局部作用域 |
| 注解 SQL | 常量 SQL 的参数补全、跳转、检查和重构；常量数组及 script 注解可离线预览，Provider 不求值 |
| SQL 编辑 | XML 动态标签各文本位置的 SQL 上下文、关键字/列提示、参数及标签检查；可选 IDEA Database SQL 语言接入 |
| 控制台日志 | 可开启 Parameters 行的还原链接，按线程配对；点击才解析，不自动执行 SQL |
| 数据库执行 | 独立 JDBC 入口：查询、事务回滚试跑、预览后确认提交；参数化单语句、结果和超时上限 |
| 数据源与表结构 | 手动 JDBC 或可选 IDEA Database 的常规连接配置；读取表、字段、主键，密码使用 PasswordSafe |
| MCP | IDEA 内置 HTTP 服务上的 8 个工具，项目令牌和本机访问；默认关闭，数据库访问单独开启，不提供提交 |
| Kotlin | 可选 K2 接入，Mapper/XML 导航、参数/属性解析、常量 SQL 注解预览、直接字符串 @Param 绑定重构 |
| Spring | 可选 MapperScan/MapperScans Bean 识别、扫描配置检查；标准 Boot 自动配置中的 @Mapper 支持 |
| 映射与测试生成 | SELECT → resultMap（含嵌套映射）；接口方法 → 独立 MyBatis JUnit SQL/参数断言与显式开启的回滚测试 |
| 代码生成 | DDL → 实体/Mapper/XML（MyBatis / MyBatis-Plus）；方法名 → 简单查询；实体 → DDL；预览后创建文件，不覆盖已有文件 |
| SQL 日志还原 | Preparing / Parameters 配对、转义、null、数值和线程交错检查 |
| MyBatis SQL 预览 | MyBatis 3.5.19 SQL 节点与 OGNL，使用单次请求的表达式缓存处理 if、where、set、trim、choose、foreach、bind、include |
| iBATIS SQL 预览 | 独立处理 dynamic、旧条件标签、prepend、removeFirstPrepend、iterate、include、内联参数与静态 parameterMap |

预览显示预编译 SQL 和有序参数，**不连接数据库，也不代表业务 SQL 已验证**。数据库执行使用独立入口。离线 MyBatis 求值限定 JSON 和常用无副作用方法，拒绝 Java 静态成员、反射、构造器、自定义 TypeHandler 及无法受限展开的脚本；iBATIS 使用独立求值器。尚未完整覆盖原插件，完整 SQL/OGNL 类型语义、全部 Kotlin/Spring 配置和自定义代码模板仍有边界。详见 [功能范围](docs/features.md) 与 [运行接入说明](docs/runtime.md)。

## 使用

- **鱼形图标与跳转**：Mapper XML / iBATIS sqlMap，以及通过 XML 关联或 `@Mapper` 识别的接口显示原创鱼形图标，XML 为红色、接口为蓝色，支持浅色/深色主题。跳转标记使用目标类型的颜色。点击接口或方法左侧的小鱼跳到 XML；点击 XML namespace / 语句 id 左侧的小鱼跳回接口或方法，多个目标显示选择列表。Settings → Tools → MyBatis Assistant 可关闭“显示 Mapper / XML 跳转图标及操作按钮”，文件图标仍显示。
- **生成 resultMap**：点击 SELECT 开始行左侧的绿色操作按钮 → 从 SELECT 生成 resultMap；先为表达式设唯一别名，使用实体 resultType，预览可手工补充未匹配列。
- **快速测试/testcase**：点击 Java/Kotlin Mapper 方法名左侧的绿色按钮，或 SELECT 左侧操作菜单的“测试 Mapper SQL / 生成 testcase”；编辑 JSON、测试分支后预览并创建 JUnit 测试与配置。默认只断言渲染 SQL/参数；数据库测试需显式启用 `-Dmybatis.test.database=true` 并配置 `mybatis.test.jdbc.url/driver/user/password`，在测试数据库执行并回滚。
- **生成 CRUD**：粘贴单表 CREATE TABLE，选择包名、框架、方言和目标模块，先预览再写入。
- **日志转 SQL**：粘贴同一线程、完整配对的 MyBatis 日志，点击还原。
- **动态 SQL 预览**：保持 mapper / sqlMap 为当前编辑器，光标放在语句内，输入 JSON 后预览；也可填写语句 id。
- **iBATIS 参数**：JSON 对象表示 Map，支持根数组、标量和 null。集合标签使用标准 #items[].property# 或 #[].property#。
- **自定义 namespace**：Settings → Tools → MyBatis Assistant，每行填写 `接口全名=namespace`。
- **类型别名**：自动读取配置 XML 与常量 `mybatis.type-aliases-package` / `mybatis-plus.type-aliases-package`；复杂配置可在设置中补充包名或 `别名=类全名`。
- **重命名**：在 `@Param` 字面值、映射 id、foreach/bind 声明或对应 XML 引用上使用 IDEA Rename（Shift+F6）。Java 参数标识符仍使用普通 Java 重构，保留显式 @Param 绑定名。
- **控制台还原**：在设置中开启控制台 SQL 链接，点击 `Parameters:` 行打开还原结果；没有线程标识且日志交错时跳过配对。
- **SQL 注解预览**：光标放在带常量 @Select/@Insert/@Update/@Delete 的方法内，使用动态 SQL 预览；JSON 键与实际 MyBatis 参数绑定名一致。
- **数据库执行**：在设置的“数据库 / MCP / Spring”页配置 JDBC，或使用已配置的 IDEA Database 数据源；Tools → MyBatis Assistant → 数据库执行，刷新数据源，先预览再选择查询/回滚/确认提交。
- **MCP**：应用“启用此项目 MCP”设置，再从 Tools 菜单查看连接配置；分别提供 JSON 客户端与 Codex TOML。数据库读取和 DML 回滚试跑分别授权，服务不提供提交；重启后需重新复制项目令牌。
- **Kotlin/Spring**：安装并启用对应 IDEA 扩展后自动接入；当前验证 K2。Spring 支持字面量 MapperScan/MapperScans，以及标准 MyBatis/MyBatis-Plus Boot 自动配置；动态配置和自定义工厂/命名器明确跳过。

`examples/demo` 和 `examples/ibatis` 提供可导入的 Maven 示例。[iBATIS 说明](docs/ibatis.md) 记录导航规则、求值语义和限制。MyBatis 稳定参数绑定建议显式使用 @Param；iBATIS 使用旧参数对象规则。

## 构建与验证

需要 Cargo/Rust、Python 3、JDK 25 与本地 IDEA 2026.3 SDK；本地脚本按 SDK 版本选择编译器，仍使用 `--release 21` 生成插件字节码。

```sh
python3 scripts/build.py --test --bench
python3 scripts/test_legacy.py
python3 scripts/test_ide.py
python3 scripts/test_database.py
python3 scripts/test_ide.py --integrations
python3 scripts/verify_archive.py
```

本地构建不另下载 IDEA，也不启动 Gradle 常驻进程。`IDEA_HOME` / `--idea-home` 可指定 IDEA 路径；iBATIS 2 与 H2 依赖只用于测试，不进入发布包。可选集成代码在构建时需要 SDK 自带 Database/Kotlin/Spring 扩展，安装时仍为可选依赖。IDEA 测试使用隔离配置，H2 测试使用临时内存库，不连接用户数据库。

标准 IntelliJ Platform Gradle 工程：

```sh
JAVA_HOME=/path/to/jdk25 ./gradlew --no-daemon buildPlugin
JAVA_HOME=/path/to/jdk25 ./gradlew coreTest
JAVA_HOME=/path/to/jdk25 ./gradlew runIde
```

### GitHub 自动构建

源码仓库：[HarryJLS/mybatisplus](https://github.com/HarryJLS/mybatisplus)，主分支为 `main`。向 `main` 推送代码会触发 [Build and release plugin](https://github.com/HarryJLS/mybatisplus/actions/workflows/build-plugin.yml)；也可在 Actions 手动运行。Pull Request 执行测试和打包，成功发布只在 `main` 上进行。

流水线下载固定 **IDEA 2026.3** SDK，使用 JDK 25、Rust stable，在四个平台运行 Rust 测试、Java/JNI/MyBatis 核心断言和归档/注册实现/原生库架构校验。四个平台构建成功后，合并为 **`mybatis-assistant-版本.zip`**，校验共享 Java 代码/依赖一致和全部原生库架构，再在四个平台从这个最终 ZIP 加载原生引擎并测试日志/DDL。Actions 的 **`mybatis-assistant`** 附件保留统一安装包、SHA256 和报告 30 天；最终 ZIP 的四平台测试全部通过后，自动创建 Release `v插件版本-build.流水线编号` 并上传这三个文件。普通 push 不必修改版本或创建 tag；编号让同一插件版本的不同构建也能分别下载，失败的构建不会发布 Release。

IDEA 界面集成测试、可选 Kotlin/Spring/Database 专测及 H2/iBATIS 运行时对照仍使用上面的本地命令。自动打包通过不代表已验证全部 IDE 操作或真实生产数据库。发布的统一 ZIP 可直接用于已覆盖的系统/处理器组合；其他组合需另行编译原生库。

本地默认使用已安装的 IDEA；需要下载 SDK 时使用 `./gradlew --no-daemon -PideaVersion=2026.3 coreTest buildPlugin`。Windows 可用 `gradlew.bat`。单台机器的本地构建输出在 `build/distributions/`，带平台后缀，仅包含本机原生库；CI 的 `platform-build-*` 是合并所用中间附件。统一安装包位于 `build/universal-artifacts/`，命名不带平台后缀。发布任务使用本次流水线的 GitHub 临时令牌，无需设置个人令牌或 Marketplace 密钥。

性能设计和样例数据见 [性能报告](docs/performance.md)。`MyBatisCodeHelper-Pro/` 为本地参考资源约定目录，不纳入源码上传或发布包，当前源码和构建不依赖它；结构分析见 [参考资源说明](docs/reference-analysis.md)。

## 版本记录

日期采用 Asia/Shanghai。0.1.0–0.5.0 的功能最初在本地开发，没有独立的历史 Git commit；2026-11-21 首次上传以当前完整源码快照建立 Git 历史。历史条目的“未提交”表示该历史版本没有独立提交，不据此虚构提交记录。

### 0.5.8 — SELECT / Mapper 方法左侧操作按钮

- **更新日期：2026-11-24；提交日期：2026-11-24。**
- 已实现：SELECT 开始标签名旁增加绿色操作按钮，点击打开生成 resultMap / 测试 SQL / 生成 testcase 菜单；没有可解析接口方法时只提供 resultMap 操作。
- 已实现：已有 MyBatis XML 语句的 Java/Kotlin Mapper 方法名旁增加绿色测试按钮，直接打开参数测试与 testcase 窗口；保留鱼形双向导航和 Alt+Enter 快捷入口。
- 每个声明只在名称叶节点生成一次按钮，过滤 XML 属性、结束标签及普通方法；iBATIS 不显示 MyBatis 生成按钮。点击使用 SmartPsiElementPointer 获取当前目标，生成/测试仍复用后台任务和预览流程，不在高亮时生成代码或扫描项目。
- 同步设置开关名称、使用说明、开发版本及安装包地址。
- 验证：`python3 scripts/build.py --test`（18 项 Rust 测试、187 项核心断言及 4 个生成的 JUnit 测试编译/真实 MyBatis 对照）、`python3 scripts/test_ide.py`（111 项）、`python3 scripts/test_ide.py --integrations`（13 项）及 `python3 scripts/verify_archive.py`（50 个注册实现与归档/原生库检查）通过。新增 SELECT 开始行/属性/结束标签去重、菜单操作、Java/Kotlin 方法按钮、设置开关、失效目标禁用及原鱼形导航保留验证。实际平台为 IDEA 2026.3（263.7307.200）/ macOS aarch64，较早平台未重跑本版完整验证；本次按钮变更未执行用户数据库操作。

### 0.5.7 — 所选 SQL 编辑、映射与测试能力完善

- **更新日期：2026-11-22；提交日期：2026-11-24。** 随 0.5.8 同次提交；0.5.7 没有独立 Git 提交。
- 动态 SQL 编辑：为 XML 各文本位置构造有界 SQL 上下文，处理 where/set/trim/choose/foreach/include/selectKey 与复用 sql 片段；where 前缀覆盖规则支持空格、换行与 Tab；可选 IDEA Database 提供 SQL 语言提示与静态语法检查，条件不求值，不表示所有运行分支或数据库语义已验证。
- 参数与 OGNL：补充 #{} 选项/引号及注释内绑定检查、${} 表达式检查、动态标签结构检查；解析泛型方法返回值、Map 索引键与 foreach 键/值，按声明顺序推导 bind 类型，处理 include 及条件分支声明，未引用片段/其他语句不泄漏变量；复杂重载与条件绑定的未知类型保留边界。
- resultMap：新增变化文件驱动的引用索引，按引用解析分文件/跨 namespace/extends/嵌套 columnPrefix；提示未显式映射的 SELECT 列；SELECT 上 Alt+Enter 可生成含 association/collection 的 resultMap，歧义列在可编辑预览中明确列出，通配符/动态投影拒绝自动生成。
- if test：支持选中 SQL 或光标所在 AND/OR 条件行，生成嵌套空值保护；数字索引先检查数组长度或 List 大小，Map 字符串键保持完整。
- testcase：接口方法 Alt+Enter 打开参数测试窗口，按 JSON 渲染后生成独立 MyBatis 配置与 JUnit 4/5 测试（SQL 和有序参数断言）；可显式开启数据库回滚测试。配置读取原 Mapper 资源，不启动 Spring，不保存数据库密码，不覆盖已有文件；数据库测试需项目提供 JDBC 驱动、连接、有效数据和业务断言。
- 验证：`python3 scripts/build.py --test`（18 项 Rust 测试、187 项核心断言及 4 个生成的 JUnit 4/5 SELECT/UPDATE 测试源码编译/真实 MyBatis SQL 与绑定对照）、`python3 scripts/test_ide.py`（106 项）、`python3 scripts/test_ide.py --integrations`（12 项）、`python3 scripts/test_database.py`（30 项真实 H2 JDBC/事务断言及生成测试的查询/更新回滚验证）、`python3 scripts/verify_archive.py`（47 个注册实现及归档/原生库检查）通过。实际平台为 IDEA 2026.3（263.7307.200）/ macOS aarch64；较早平台未重跑本版完整验证。未执行用户数据库或生产数据库验证，未将离线渲染写成业务 SQL 已验证。

### 0.5.6 — 双色鱼形图标与 XML 导航去重

- **更新日期：2026-11-22；提交日期：2026-11-22。**
- 已实现：按用户截图重新绘制深色鱼身与彩色鱼头的原创 SVG；XML 文件用红色，Java/Kotlin Mapper 接口用蓝色，导航标记按目标类型配色（接口→XML 红色，XML→接口蓝色），含浅色/深色主题版本。
- 已修复：namespace / 语句 id 仅在属性内容的首个叶节点生成鱼形导航标记，过滤起止引号造成的两个重复菜单项；单引号、双引号采用相同规则。
- 验证：18 项 Rust 测试、171 项核心断言、82 项 IDEA 集成测试、10 项可选扩展测试及 macOS aarch64 安装包的 43 个注册实现/归档检查通过。新增完整 XML 叶节点收集回归，验证每个属性仅一个标记、单双引号无标记、双向导航目标和目标图标；四种 SVG 经 IDEA 渲染器检查及浅色/深色图片检查。兼容声明保持 261–263.*，本次实际平台为 IDEA 2026.3（263.7307.200）/ macOS aarch64，较早平台未重跑完整测试。

### 0.5.5 — Mapper 文件与导航鱼形图标

- **更新日期：2026-11-22；提交日期：2026-11-22。**
- 已实现：原创 16×16 鱼形 SVG 及深色主题版本，统一 Mapper XML / iBATIS sqlMap 文件、Java/Kotlin Mapper 接口与编辑器跳转标记；通过 IDEA IconProvider 接入项目树、文件标签与类展示。
- 已实现：XML 根 namespace 旁增加跳回 Mapper 接口的鱼形图标；保留接口→XML、方法↔语句、多目标选择与 iBATIS DAO 点击后查找实现。导航开关沿用现有设置，文件图标独立显示。
- 兼容限制：接口通过 `@Mapper` 或现有 XML namespace / 显式映射识别，普通 XML 和仅以 Mapper 结尾的接口不改图标；索引构建期间接口图标使用默认展示，Kotlin 需启用可选 K2 扩展。图标独立绘制，未复制参考插件资源。
- 验证：18 项 Rust 测试、171 项核心断言、81 项 IDEA 集成测试及 10 项可选扩展测试通过；macOS aarch64 安装包的 43 个注册实现及归档检查通过。用户已安装并确认鱼形图标显示；后续发现 XML 引号节点产生重复菜单项，在 0.5.6 修复。兼容声明保持 261–263.*，本次实际平台为 IDEA 2026.3。

### 0.5.4 — 同 namespace 分文件预览与按需 include

- **更新日期：2026-11-22；提交日期：2026-11-22。**
- 修复真实项目发现的同一 namespace 分布于多个 XML 时，本地 refid 无法读取其他文件 SQL 片段的问题；按持久 namespace 索引定位，编辑期间不扫描全项目。
- GUI、数据库入口与 MCP 共用 MapperSnapshot，只遍历当前语句实际引用的片段；按文件去重，按请求复用 namespace 查询，保留文件数量、输入、深度与步数上限。
- 构建：IntelliJ Platform Gradle 插件更新到 2.20.1、Gradle 分发更新到 9.2.1 并固定 SHA256，使用 JDK 25 读取 263 SDK、保持 Java 21 字节码目标；CI 改为 IDEA 2026.3 / JDK 25。
- 验证：18 项 Rust 测试、171 项核心断言、74 项 IDEA 集成测试、9 项可选扩展测试、JDK 25 标准 Gradle `coreTest buildPlugin` 和归档检查通过；新增同 namespace 嵌套片段、按需 include 和 MyBatis 跨 namespace 裸 refid 调用方语义测试。已通过 IDEA 官方启动安装队列更新并由重启日志确认加载 0.5.4，临时界面探针已移除。现场复测 80 个接口、372 个方法全部解析；79 个预览中 68 个成功，原先 4 个同 namespace 片段失败案例全部通过，其余 11 项明确拒绝业务方法求值。10000 次不同表达式预览静态缓存为 0 → 0，真实 IDEA 中 JNI 日志/DDL 与懒加载检查通过；本次 5 分钟压力采样和 2 分钟 GC 后观测详见性能报告，未扩大为数天验证。

### 0.5.3 — IDEA 2026.3 接入与预览缓存

- **更新日期：2026-11-21；提交日期：2026-11-22。**
- 已修复：XML 声明值的父节点不是普通 XmlAttribute 时直接跳过，避免在带 XML 声明的 Mapper 高亮/补全中抛出 ClassCastException。
- 已实现：IDEA 2026.3（263.7307.200）的独立 XML SDK 模块依赖、按 SDK 版本隔离的测试依赖和测试配置、完整测试插件加载。兼容声明保留 261 下限；本次完整接入验证为 263，较早平台未重跑本版本完整测试。
- 已实现：MyBatis 预览表达式改为单次渲染拥有的语法缓存（最多 256 条/256 Ki 字符，单条仍受语法树上限约束），沿用真实 MyBatis SQL 节点与 foreach 参数规则；不再将用户表达式存入 MyBatis 静态缓存，补充后台任务取消检查。
- 2026-11-22 复测：18 项 Rust 测试、171 项核心断言、71 项 IDEA 集成测试和 9 项可选扩展测试通过；核心测试用真实 MyBatis 对照并验证 2000 次不同表达式预览不增加静态缓存。已安装到 IDEA 2026.3 并由启动日志确认加载。真实业务项目 80 个 XML、372 个方法的解析通过，2 轮各 10000 次预览静态缓存均为 0；完整 IDEA 进程已采样 20 分钟，范围和剩余限制见性能报告。

### 0.5.2 — 编辑与文本工具性能优化

- **更新日期：2026-11-21；提交日期：2026-11-22。**
- 已实现：类型别名由同模块 XML 共享字符串缓存，按 Java/Kotlin 类型、配置索引、项目根和设置变化失效；普通 SQL 编辑不再重建别名表。
- 已实现：类属性表附着于类 PSI 共享，保留继承字段/getter 与泛型替换，复用声明智能指针；XML 检查仅在存在表达式或占位符时读取参数模型，OGNL 编辑语法树按属性缓存。
- 已实现：Mapper 元数据共享 resultMap 的 SELECT 反向引用；静态投影/列缓存追踪实际 include 文件，并在缺失片段对应的索引变化后重试；展开前检查输出长度并补充取消检查。
- 已实现：Rust DDL 逐段借用输入，常规无注释输入避免复制和全量小写转换，字段超限提前退出；日志结果直接写入一个输出字符串。
- 兼容限制：仍声明 IDEA 2026.1（261.*），本次未扩展到 2026.2/2026.3；MyBatis 预览仍使用真实引擎及其表达式缓存，未声称已解决长期预览缓存累积或完成真实项目 CPU/内存对照。
- 验证：本地 IDEA 2026.1.4 / macOS arm64，18 项 Rust 测试、152 项核心断言、69 项 IDEA 集成测试及 9 项实际 Kotlin/Spring/Database 扩展测试通过；含新增缓存共享、泛型、Java/Kotlin 修改失效、跨文件 include 与缺失片段重试测试。macOS 安装包的 41 个注册实现/归档检查和从 ZIP 加载 JNI 的日志/DDL 测试通过。
- 实测：与变更前 HEAD 的独立 Rust release 对照，4 列 DDL 7 轮中位耗时由 0.785 降至 0.652 微秒/次；1000 列 DDL 累计分配请求字节由 253908 降至 134248（约减少 47%），100 组日志由 53346 降至 39460（约减少 26%）。详见 [性能报告](docs/performance.md)；不代表完整 IDEA 的 CPU/常驻内存，也未验证本次云端四平台安装包或生产数据库。

### 0.5.1

- **更新日期：2026-11-21；提交日期：2026-11-21。**
- 发布改为单个 `mybatis-assistant-0.5.1.zip` 插件安装包，包含 macOS aarch64/x86_64、Windows x86_64、Linux x86_64 四套原生库；现有加载器按当前系统自动选择，无需用户选不同平台的 ZIP。
- CI 保留平台编译中间附件，合并时核验输入完整性、Java 代码/依赖一致和原生库架构；Actions 的 `mybatis-assistant` 与 Release 只交付统一插件 ZIP、`SHA256SUMS.txt`、`verification.json`。增加四个平台从最终 ZIP 实际加载 JNI 并执行日志/DDL 的测试，全部通过才发布。
- 同步插件、Gradle、MCP 版本及安装说明，明确区分插件 ZIP、GitHub 源码 ZIP 和开发用平台中间包；Windows ARM64、Linux ARM64 等未覆盖组合不声称通用兼容。
- 验证：0.5.1 本地 15 项 Rust 测试、152 项核心断言、JDK 21 标准 Gradle `coreTest buildPlugin`、macOS 安装包的 41 个注册实现/归档检查和从 ZIP 加载 JNI 的日志/DDL 测试通过；流水线配置通过 actionlint。已发布四个平台的 0.5.0 构建样本成功合并为统一 ZIP，全部原生库架构及注册实现检查、本机从该 ZIP 自动加载 JNI 通过。本次云端 0.5.1 最终安装包的四平台结果以对应 Actions 记录为准，未扩大为 IDE 界面或生产数据库验证。

### 0.5.0 — 首次源码发布与自动打包

- **更新日期：2026-11-21；提交日期：2026-11-21。** 保持插件功能版本 0.5.0，本次新增源码仓库与构建发布流程，以当前源码快照首次提交。
- 新增 `main` push、Pull Request 和手动构建；CI 下载固定 IDEA 2026.1.4 SDK，保留本地 SDK 构建，补充 Windows Gradle 启动入口。
- 修正 Gradle `coreTest` 的 SDK 运行依赖，确保 namespace XML 测试能够加载 NanoXML；依赖仅用于测试，不额外打包 IDEA SDK。
- 新增四平台独立安装包、SHA256、注册实现与原生库架构检查；成功构建在 Actions 提供附件，`main` 全部通过后创建唯一编号 Release。
- 排除生成目录、Python 缓存及参考插件资源；生产包拒绝测试依赖，GitHub 发布使用任务级临时令牌。
- 验证：本地 `scripts/build.py --test` 的 15 项 Rust 测试与 152 项核心断言通过；JDK 21 标准 Gradle `coreTest buildPlugin`、macOS Apple Silicon 安装包的 41 个注册实现/原生库架构/归档检查、CI 附件命名与校验文件生成通过。流水线配置通过 actionlint 检查。云端每次运行的四平台结果以对应 Actions 记录为准，未扩大为 IDE 界面或生产数据库验证。

### 0.5.0

- **更新日期：2026-11-21；提交日期：未提交。**
- 新增独立数据库执行入口：参数化 SELECT/INSERT/UPDATE/DELETE，支持查询、事务回滚试跑和确认提交；直接 JDBC 连接完成后关闭，原离线预览入口保持不连接数据库。
- 新增手动 JDBC 配置及可选 IDE Database 数据源读取；密码保存在 PasswordSafe。暂不接管已有控制台事务，不支持 IDE SSH、额外 SSL 配置、初始化脚本或自定义认证流程。
- 新增数据库表/字段/主键元数据读取，指定精确数据源与 schema；同名表歧义报错。执行限制为单语句、最多 1000 行、256 列、256 KiB SQL/文本结果、10000 参数及 1–60 秒 JDBC 超时；WITH、DDL、CALL 等暂不执行。
- 新增项目级 MCP：复用 IDEA 内置 HTTP 服务，提供 8 个非代码生成工具；Streamable HTTP JSON 响应、项目令牌、仅本机连接、Origin/Host 校验及最多 4 个并发请求。默认关闭，数据库访问及 DML 回滚试跑分别开启；MCP 不提供事务提交或代码生成。
- 可选 Kotlin 接入通过平台轻量 Java 模型实现 Mapper/XML 导航、参数/属性解析、映射检查和常量 SQL 注解预览；suspend Mapper 明确不支持，字符串模板插值、完整 Kotlin 注解编辑与重构仍有边界。
- 可选 Spring 接入提供字面量 MapperScan/MapperScans 解析、包/标记接口/注解过滤、Mapper Bean 识别及缺失包提示；使用定向索引与随 PSI 失效的指针缓存，动态占位符、默认包扫描和自定义命名/工厂跳过。
- 增加离线渲染执行边界：JSON 嵌套/数值精度限制，OGNL 静态成员/反射/构造器/赋值/投影与自定义 javaType/typeHandler 拒绝求值；include 总展开和动态 SQL 保守上界在求值前检查，OUT/INOUT 参数只显示而不执行。
- 修正 Spring Bean 名称参数顺序，避免多个 Mapper 被登记成相同名称；Boot 自动 Mapper 识别要求实际自动配置入口，并在存在显式 MapperScan 时退出自动补充。
- 直接字符串 Kotlin @Param 重构在修改前验证源注解，支持导入别名，避免只修改 XML 后无法同步声明。suspend、复杂注解表达式和完整 Kotlin 语义重构未覆盖。
- 验证：15 项 Rust 测试、56 项原核心断言、38 项独立 iBATIS 求值断言、24 项编辑核心断言、25 项运行/MCP 核心断言及 9 项离线边界断言通过；62 项 IDEA 集成测试（含真实内置 HTTP 请求）、8 项实际 Kotlin/Spring/Database 扩展专测、30 项真实 H2 JDBC/事务断言及 71 组真实 iBATIS SQL/参数对照通过。
- 实际数据库验证仅使用临时 H2：查询、提交、回滚、失败/取消后不提交、行数/文本上限和表结构。未连接用户数据库，未验证生产 MySQL/PostgreSQL/Oracle、全部认证/驱动环境、业务断言或长期性能。
- 标准 JDK 21 / Gradle `buildPlugin` 通过；安装包的 41 个主/可选扩展实现、原生库及归档完整性检查通过。当前 macOS Apple Silicon 安装包约 2.29 MiB，README 安装链接和受影响功能/运行/性能文档已同步。

### 0.4.0

- **更新日期：2026-11-17；提交日期：未提交。**
- 根据官方 `3.6.9+2321` 与本地参考资源核对，补充非代码生成的编辑、检查、重构和调试能力；本次不扩展代码生成。
- 新增持久类型别名配置索引，支持 MyBatis 内置别名、XML typeAliases、常量 properties/YAML 包配置、包内 @Alias 与显式设置；按模块范围解析，同名冲突不猜测。限定最多 128 个配置文件、256 个包和 4096 个类；通配包名、环境占位符、复杂 YAML 与自定义注册器未覆盖。
- 新增有界 SELECT 输出列解析和 include 展开，支持 resultMap 列补全、重复/缺失列检查与内联 columnPrefix；含 `*`、动态列或无法确定输出名的表达式时不执行缺失列判断，不声称数据库语义验证。
- 使用 MyBatis 内置 OGNL 解析器检查语法、已知参数/属性和方法参数数量，补全零参数方法，提示 String 与单字符比较；编辑期间不调用业务方法。动态 Map、bind 推导类型、方法返回值链、投影/lambda 上下文及完整重载类型推导仍有边界。
- 支持 @Param 绑定名、sql/resultMap/parameterMap id、foreach item/index 与 bind name 重构，保持 Java 参数标识符、跨 namespace 引用前缀和嵌套局部变量；补充父 Mapper 泛型实参解析，跨文件声明以 SmartPsiElementPointer 保存。
- 常量 SQL 注解支持参数补全、引用、检查和重构；常量数组/字符串拼接及 script 可离线预览。script 的编辑检查/参数和局部变量重构、Provider 求值和注解迁移未实现；script 方法及来自外部常量/拼接表达式的注解拒绝自动 @Param 重构，防止只改绑定声明或误改遮蔽变量。
- 可选 IDEA Database 依赖提供静态 SQL 的平台语言注入，保留 Java/XML 转义与绑定占位符，跳过动态标签、组合注解和 `${}` 标识符；不读取数据源，不连接数据库。
- 控制台增加按线程有界配对和 Parameters 还原链接，默认关闭，点击后后台还原；不增加工作线程、轮询或自动 SQL 执行。
- 验证：15 项 Rust 测试、56 项原有核心断言、38 项独立 iBATIS 求值断言、24 项新增编辑核心断言、51 项 IDEA 集成测试及 71 组真实 iBATIS SQL/参数对照通过；安装包的 30 个主/可选扩展实现与原生库检查通过。数据库元数据/执行、真实 Spring/Kotlin 项目、完整 SQL 语言注入界面体验和长期性能未验证。
- 标准 JDK 21 / Gradle `buildPlugin` 构建通过；同步插件/Gradle 版本及安装包地址，保留历史版本与原资源包。

### 0.3.0

- **更新日期：2026-11-16；提交日期：未提交。**
- 新建 AGENTS.md，说明架构、目录职责、性能约束、验证方式；要求每次功能更新同步 README 的版本说明和提交日期。
- 重整 README，补齐项目说明、安装、验证方法及 0.1.0 / 0.2.0 / 0.3.0 历史。
- 新增独立 iBATIS 2 SQL 求值器，处理旧条件标签、首个 prepend 移除、嵌套 iterate、include 与有序参数绑定。
- 支持旧 #property# / JDBC 后缀、$property$、nullValue、静态 parameterMap、OUT/INOUT 占位说明；selectKey 单独标记为跳过。
- 保留旧占位符名称中的空格，不将 MyBatis 的自动修整规则套到 iBATIS；校验参数选项及 numericScale。
- 同一预览窗口支持 mapper 和 sqlMap；增加展开深度、步骤、迭代、参数数量与输出上限，拒绝循环引用和未知标签。
- 使用真实 iBATIS 2 XMLSqlSource / DynamicSql 对照关键语义；自定义 TypeHandler、业务对象构造、数据库执行及动态标签混用外置 parameterMap 不在本版离线求值范围。
- 验证：15 项 Rust 测试、56 项现有核心断言、38 项独立 iBATIS 求值断言、71 组真实 iBATIS SQL/参数对照、26 项 IDEA 集成测试全部通过；标准 Gradle 构建、21 个扩展实现/原生库打包检查、MyBatis 与 iBATIS 示例编译通过。

### 0.2.0

- **更新日期：2026-11-15；提交日期：未提交。**
- 新增 iBATIS sqlMap 识别及接口/方法双向跳转；支持全名、唯一简称、自定义 namespace 映射和无 namespace 文件匹配。
- 新增按需 DAO 实现查找，识别静态 SQL id、常量拼接与同类辅助方法；新增 SQL API 字符串导航。
- 兼容 parameterClass/resultClass、parameterMap/resultMap class、旧占位符、动态 property 和 iterate 元素类型。
- 旧 DAO 不强制接口方法和 SQL id 同名，避免 MyBatis 代理规则误报。
- 当时尚不支持旧动态 SQL 求值；该能力在 0.3.0 增加。
- 验证：15 项 Rust 测试、56 项核心断言、25 项 IDEA 集成测试；标准构建、发布包检查及两个示例编译通过。

### 0.1.0

- **更新日期：2026-11-15；提交日期：未提交。**
- 建立 Java + Rust JNI 插件工程，提供本地 SDK 构建与标准 Gradle 工程。
- 实现 MyBatis namespace 索引、接口/XML 导航、参数与映射补全及基础检查。
- 实现 DDL/CRUD、简单方法名查询、实体 DDL、SQL 骨架、if test 操作、日志还原和 MyBatis 动态 SQL 预览。
- 增加随 PSI 失效的缓存、按方法共享绑定、Rust 延迟加载、生成预览和同名文件保护。
- 增加原插件资源结构分析、性能样例与示例项目；该阶段是核心功能初版，不是原插件全部能力的复刻。
