# SQL 编辑、测试、数据库与可选接入（0.5.8）

发布时使用统一的 `mybatis-assistant-0.5.8.zip` 安装；当前 0.5.8 为未发布开发版本，本机测试包仅含 macOS aarch64 原生库。发布后的统一包供 Windows x86_64、macOS Apple Silicon / Intel 与 Linux x86_64 共用；原生引擎仍在首次使用时按当前系统加载。其他处理器/系统组合没有预编译库。数据库、MCP、Kotlin 和 Spring 的运行边界保持适用；0.5.5 新增 Mapper 文件/接口与跳转标记的原创鱼形图标，Kotlin 通过可选扩展加载。0.5.6 改为 XML 红色、接口蓝色的小鱼，跳转标记按目标类型配色，并过滤 XML 属性引号产生的重复项。

## SQL 编辑与 resultMap

XML 的 select/insert/update/delete、selectKey 和 sql 片段共享参数与 SQL 编辑支持。where/set/trim 使用前后缀规则，choose 对当前文本所在分支构造上下文，foreach 保留一轮循环体；include 按 namespace 索引解析，复用片段优先使用其引用语句的表上下文。SQL 提示与语法检查需要可选 IDEA Database；参数/OGNL/标签检查不需要 Database。条件不在编辑时求值，不穷举全部组合。动态 ${} 标识符、动态 refid 与无法有界展开的引用不注入猜测 SQL，保留参数提示与明确诊断。合成上下文不参与改写 XML 内容；XML 的标签、控制字符和参数绑定交由原 XML 格式化处理。

点击 SELECT 开始行左侧绿色按钮 → 从 SELECT 生成 resultMap。实体来自 resultType、已有 resultMap 或 Mapper 返回类型；字段/getter、明确列注解和嵌套属性路径用于匹配。嵌套路径对应 association/collection，并在明确的别名前缀上生成 columnPrefix；未匹配列写入预览注释，用户可编辑映射后写入新 resultMap 并关联 SELECT。不覆盖原 resultMap。通配符、重复输出别名和动态投影无法确定完整输出列，拒绝自动生成；应先设置唯一的静态别名。

反向 resultMap/sql 引用按 XML 文件变化持久索引，编辑不扫描全项目。展开深度最多 32、语句节点最多 2048、SQL 文本最多 256 Ki 字符；resultMap 引用最多 128 个文件/4096 个节点，实体匹配最多 512 个叶属性/6 层。超限保留不完整说明。

## 接口方法测试与 testcase

点击 Java/Kotlin Mapper 方法名左侧绿色按钮，或 SELECT 左侧绿色按钮菜单的“测试 Mapper SQL / 生成 testcase”。鱼形图标继续负责接口/XML 导航，Alt+Enter 仍可使用。输入模型提供可编辑 JSON 样例；测试按钮后台渲染当前参数分支，显示 SQL 和有序参数。生成需要唯一的 MyBatis XML 语句，预览成功后写入所属模块的 src/test/java 与 src/test/resources。参数或 XML 变化后必须重新测试，不覆盖任何已有测试文件。

测试无需 Spring；自动检测 JUnit 5/4（未检测到时使用 JUnit 5，项目需提供 MyBatis 与 JUnit 测试依赖）。配置读取原 Mapper 的 classpath 资源及解析到的引用文件/类型别名，后续修改原 XML 后可直接再次运行测试。SQL 测试使用真实 MyBatis BoundSql，断言本次渲染的 SQL 和有序参数；这只是所选输入分支的断言。数据库测试默认跳过，显式设置以下 JVM 参数后才运行，并使用非自动提交 SqlSession，结束回滚：

```text
-Dmybatis.test.database=true
-Dmybatis.test.jdbc.driver=org.example.YourDriver
-Dmybatis.test.jdbc.url=your-test-jdbc-url
-Dmybatis.test.jdbc.user=your-test-user
-Dmybatis.test.jdbc.password=your-test-password
```

测试代码不保存凭证、不自动下载驱动、不初始化生产数据。数据库测试需要提供有效测试数据和业务结果断言；仅无异常或 affected >= 0 不代表业务通过。自定义插件、工厂、TypeHandler、事务外副作用和全部方言需在项目测试配置中另行验证；iBATIS/Provider/非唯一语句不生成 testcase。回滚仍受数据库事务能力约束。

## 数据库连接与执行

Settings → Tools → MyBatis Assistant → 数据库 / MCP / Spring。可以填写 JDBC URL、驱动类、每行一个本地驱动 JAR 路径和用户名；新密码保存到 IDEA PasswordSafe，不写入项目 XML。也可以使用 IDEA Database 中的常规数据源配置。生产数据库驱动不会自动下载或打包。

Tools → MyBatis Assistant → 数据库执行。打开 Mapper XML 或普通 SQL 注解方法，将光标放在语句内；填写 JSON 参数，刷新/选择数据源，点击预览。随后可选择查询、事务回滚试跑或执行并提交；提交前会再次显示 SQL、参数和数据源供确认。每次创建独立连接，完成后关闭，不接管已有控制台事务。

允许单条 SELECT/INSERT/UPDATE/DELETE；不执行 WITH、DDL、CALL、显式事务指令、RETURNING、锁定 SELECT 等未覆盖语法。参数使用 PreparedStatement。最多 10000 个标量参数、1000 行、256 列；长文本每格最多 4096 字符，总结果文本约 256 Ki 字符。二进制/复杂 JDBC 对象显示为跳过，结果截断有标记。数值 precision/scale 最多 1000。

设置的是 JDBC 查询超时（1–60 秒），连接和网络超时仍取决于驱动/URL。回滚使用 JDBC 事务，实际可回滚性依赖数据库、表引擎与 SQL/函数的事务行为；不是业务结果或所有副作用的保证。当前自动化数据库验证仅覆盖临时 H2 标准事务表。

IDE 数据源接入只复用常规 URL、驱动、连接属性和凭证；不等同于完整 IDE 控制台连接准备流程。SSH、额外 IDE SSL 配置、初始化脚本、自定义认证及驱动 VM 环境未完整覆盖，应使用等效独立 JDBC URL或项目运行时。表结构读取需要精确 dataSource id/schema；schema 使用元数据返回的实际名称。同名表报歧义，未知可空性返回 null。

## MCP 客户端

在设置中启用此项目 MCP 并应用设置，再用 Tools → MyBatis Assistant → MCP 客户端连接配置查看 JSON 或 Codex TOML。服务复用 IDEA 内置 HTTP 端口，不创建独立监听器/执行器。令牌属于当前项目会话；关闭项目或重启 IDEA 后重新复制配置。默认禁用数据库访问，数据库读取和 DML 回滚试跑分别开启。

传输是无会话的 Streamable HTTP JSON，支持协议 2025-03-26 和 2025-06-18。版本协商会将其他版本请求降级到 2025-06-18，客户端需支持该版本。不提供 stdio、旧 HTTP+SSE、SSE 推送、后台通知或即时 JDBC 取消；GET 返回 405。只接受本机连接、合法 localhost/127.0.0.1/::1 Host、没有 Origin 的客户端请求及正确 Bearer 项目令牌。

每个项目最多 4 个并发请求，POST 最多 1 MiB UTF-8，JSON 深度最多 64，工具数据最多 512 KiB UTF-8。Mapper 路径须属于当前项目的本机内容根，文件最多 4 MiB；索引更新期间返回明确错误。

| 工具 | 当前能力 |
| --- | --- |
| list_data_sources | 数据源 id/name/kind/readOnly，不返回密码 |
| list_tables | 按 dataSource/schema/search 读取，limit 默认 100、最多 500 |
| get_table_columns | 精确表名、字段/JDBC 类型/可空性/主键 |
| find_mapper_xml | 按明确 namespace 的持久索引查找 |
| find_mapper_interface | 查找可解析的 Java/Kotlin 接口 |
| list_mapper_statements | 读取指定 XML 的声明语句，最多 2000 |
| validate_mybatis_mapper | 当前映射检查与 checksExecuted/checksSkipped；不声明所有动态分支或数据库语义通过 |
| run_mybatis_sql | 参数对象求值，execute 默认 false；允许数据库访问后可查询或 dryRun 回滚，永不提交 |

在导入 `examples/demo` 项目后，可以用以下工具参数进行离线求值：

```json
{
  "name": "run_mybatis_sql",
  "arguments": {
    "path": "src/main/resources/mapper/UserMapper.xml",
    "statementId": "findByIds",
    "parameters": {"ids": [1, 2, 3]},
    "execute": false
  }
}
```

`execute=true` 时需要 dataSource。`dryRun=true` 默认回滚；DML 还要求单独开启回滚试跑。`dryRun=false` 只允许 SELECT，并不表示提交。MCP 不提供代码生成、测试用例生成、配置写入或 commit 工具。

## 离线求值边界

原预览仍不连接数据库。MyBatis 采用原框架，但只对受限 JSON 数据求值：拒绝静态 Java 成员、构造器、反射属性、赋值、投影/lambda、自定义 javaType/typeHandler；仅允许 size/isEmpty/length/get/contains/equals 等常用方法。动态 include refid 不求值。OUT/INOUT 和跳过项可以显示，不能直接执行。

Java 入口的字符上限包括 XML 4 Mi、JSON 1 Mi、关联 Mapper 总量 8 Mi。求值前检查 JSON 深度/集合/数值精度，include 展开节点/文本及动态 SQL 保守上界（4 Mi 字符、100000 结构步骤、10000 参数）。保守估算可能拒绝实际分支较小的复杂嵌套脚本，不返回近似 SQL。iBATIS 仍使用独立旧语义求值器及原有上限。

## Kotlin 与 Spring

安装对应 IDEA 插件后启用可选接入，当前验证 K2。Kotlin Mapper/XML 通过轻量 Java 模型共享索引、参数、属性和引用；常量字符串/数组 SQL 注解可预览。直接字符串 @Param 支持绑定名重构并保留源码变量名；复杂表达式、插值、完整 Kotlin 注解编辑/语义重构未覆盖，suspend Mapper 明确提示不支持。

Spring 字面量 MapperScan/MapperScans 支持 value/basePackages/basePackageClasses、annotationClass 和 markerInterface。包范围使用平台索引与 PSI 生命周期缓存，最多 256 个包、4096 个候选类。Bean 名称按默认类名规则生成，类型保持 Mapper 接口。标准 MyBatis/MyBatis-Plus Boot 自动配置可识别 @Mapper；存在显式 MapperScan 时由显式配置接管。动态包名、元注解、自定义命名器/工厂、复杂条件和 XML Spring 扫描未完整覆盖，明确跳过。

这些是独立实现的基础兼容能力，不宣称完整复刻原插件。数据库执行结果、离线渲染、IDE 检查和业务测试分别报告。

## IDEA 2026.3 与单次预览缓存

0.5.3 起完整接入测试使用 IDEA 2026.3 / JBR 25，构建需要能读取 Java 25 SDK 类的编译器，插件自身输出仍为 Java 21 字节码。更新插件通过 IDEA 的启动安装队列在重启时生效，避免运行中覆盖已打开的 JAR。

预览沿用 MyBatis 3.5.19 SQL 节点、OGNL 与参数规则，表达式缓存由单次请求拥有，不再写入 MyBatis 的静态表达式缓存；不求值业务类方法。0.5.4 的 GUI、数据库入口与 MCP 共用按需快照，只收集当前语句实际使用的 include 文件，支持同 namespace 分文件 SQL 片段；仍保持输入、深度、步骤与输出限制。

0.5.4 已在本机 IDEA 2026.3 重启后确认加载，同 namespace 分文件片段的现场失败案例已通过；真实进程的缓存累积、有限时长 CPU/RSS 和原生懒加载结果见 [性能报告](performance.md)。业务对象自定义方法仍不在离线求值范围内。
