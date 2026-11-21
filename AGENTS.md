# 项目说明与协作约定

## 项目定位

MyBatis Assistant 是独立实现的 IntelliJ IDEA 插件，提供 MyBatis 3 / iBATIS 2 的 XML 导航、参数补全、映射检查、代码生成与 SQL 预览。当前目标平台为 IDEA 2026.1（261.*）、Java 21+；原生库按构建主机编译，CI 合并为包含 macOS aarch64/x86_64、Windows x86_64 和 Linux x86_64 原生库的统一插件 ZIP。

`MyBatisCodeHelper-Pro/` 是用户提供的参考资源包。保留该目录；不把其中的程序或资源复制进发布包。当前版本的功能范围和限制以 README.md 与 docs/ 为准，不声称完整复刻原插件。

## 当前架构

- `src/main/java/dev/mybatis/assistant/ide/`：IDEA 接入层。使用 PSI 和平台索引实现 Java/XML 引用、行标记、检查、补全、设置及工具窗口。
- `MapperNamespaceIndex` / `NamespaceScanner`：持久 namespace 与 iBATIS 语句索引。MyBatis 读取 XML 根属性；iBATIS 流式读取同一变化文件的语句 id。
- `MapperModel`：按 namespace / 接口 / 显式映射解析 XML；不在补全时扫描整个项目。
- `ParameterModel`：按方法共享参数模型，局部 foreach / iterate / bind 作用域按需扩展；缓存随 PSI、索引和设置变化失效。
- `LegacyNavigation` / `SqlApiReferences`：iBATIS DAO 与 SQL API 导航。实现类和辅助方法查找只在用户明确导航时进行。
- `src/main/java/dev/mybatis/assistant/core/`：独立功能层。生成代码、DDL、XML 扫描与 SQL 渲染。`SqlRenderer` 是预览统一入口，按 XML 根元素分派 MyBatis / iBATIS。
- `native/`：Rust JNI 文本引擎，负责 DDL 和日志解析。首次使用时加载，同进程调用，不创建常驻进程或自建工作线程。Rust 组件版本与插件版本可以独立演进。
- `src/main/resources/META-INF/plugin.xml`：插件 id、版本、兼容范围和扩展声明；`build.gradle.kts` 的版本需同步。
- `src/test/`：核心断言与隔离的 IDEA 集成测试。`examples/`：可导入的示例项目。
- `scripts/`：本地 SDK 构建、IDEA 测试、包验证和参考资源结构分析。`build/`、`native/target/` 为生成目录，不当作源码修改。

## 性能与正确性

1. 禁止添加编辑期间的全项目扫描、定时轮询、自建常驻线程或每次补全启动外部进程。优先使用平台持久索引和随 PSI 生命周期管理的缓存。
2. CachedValue 的计算闭包仅持有自己的 PSI 所有者；跨文件元素通过按需解析或 SmartPsiElementPointer 管理。避免静态集合保留项目 PSI。
3. 耗时工作使用 IDEA 后台任务；UI 更新回到界面线程；修改文件使用 WriteCommandAction；生成前预览，已有文件不自动覆盖。
4. 预览只求值和显示 SQL/参数，不连接数据库。XML 不加载外部 DTD、实体或网络资源。
5. iBATIS 与 MyBatis 的标签、参数与 prepend 规则必须分别处理，不能用字符串替换冒充兼容。未知标签、歧义和无法求值的情况明确报错或说明跳过范围。
6. 计算任务需要输入、展开深度、迭代和输出上限。性能数据标注样例与实际测量范围，不把独立 Rust 程序的内存当作 IDEA 整体内存。

## 验证与交付

涉及核心行为：`python3 scripts/build.py --test`；涉及 IDEA 接入：再运行 `python3 scripts/test_ide.py`；发布包：`python3 scripts/verify_archive.py`。标准构建使用 `JAVA_HOME=/path/to/jdk21 ./gradlew --no-daemon buildPlugin`。

测试应验证行为和兼容语义；旧动态 SQL 的关键分支用真实 iBATIS 运行时做测试参考，但不因此把旧运行时打包进插件。真实数据库验证需单独列出，不能把渲染通过写成业务 SQL 已验证。

## 必须维护 README 版本记录

**每次新增、修改或修复功能，都必须在同一变更中更新 README.md 的版本记录，说明对应版本、更新点和提交日期。不能只修改源码或其他文档而遗漏 README。**

- 记录版本、更新日期、提交日期、具体功能/修复、兼容限制和实际测试范围；日期使用 Asia/Shanghai 的 `YYYY-MM-DD`。
- 尚未提交时，提交日期写“未提交”，并保留本次实际更新日期；实际提交后填写真实提交日期。首次 Git 导入为当前源码快照，历史本地版本没有独立提交，不伪造提交、提交日期或 commit hash。
- 保留历史版本，新增版本放在前面；区分“已实现”“基础兼容”“未实现”，不把计划写成已完成。
- 发布时同步 plugin.xml、Gradle 版本、README 当前版本/安装包链接及受影响的 docs/、示例。更新日期不能代替对实际验证结果的说明。
