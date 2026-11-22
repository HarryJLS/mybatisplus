# 数据库、MCP、Kotlin 与 Spring（0.5.6）

发布时使用统一的 `mybatis-assistant-0.5.6.zip` 安装；当前 0.5.6 为未发布开发版本，本机测试包仅含 macOS aarch64 原生库。发布后的统一包供 Windows x86_64、macOS Apple Silicon / Intel 与 Linux x86_64 共用；原生引擎仍在首次使用时按当前系统加载。其他处理器/系统组合没有预编译库。数据库、MCP、Kotlin 和 Spring 的运行边界保持适用；0.5.5 新增 Mapper 文件/接口与跳转标记的原创鱼形图标，Kotlin 通过可选扩展加载。0.5.6 改为 XML 红色、接口蓝色的小鱼，跳转标记按目标类型配色，并过滤 XML 属性引号产生的重复项。

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
