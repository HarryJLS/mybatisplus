# 功能范围对照

参考：JetBrains Marketplace 插件介绍、官方文档与本地 `3.6.9+2321` 资源包。当前开发版本为 `0.5.6`（未发布）；发布时统一插件 ZIP 共用于 Windows x86_64、macOS Apple Silicon / Intel 和 Linux x86_64。

| 能力 | 当前实现 | 范围 / 限制 |
| --- | --- | --- |
| Mapper/XML 跳转 | 已实现 | Java Mapper namespace 与方法 id |
| Mapper 鱼形图标 | 已实现 | 原创浅色/深色 SVG；XML 红色、接口蓝色，跳转标记按目标类型配色；过滤属性引号重复标记；项目树、编辑器标签与 Java/Kotlin 接口展示；接口/方法→XML、XML namespace/语句 id→接口/方法使用可点击鱼形标记，多目标保留选择列表；接口按 @Mapper 或 XML 关联识别，Kotlin 为可选 K2 接入 |
| namespace 持久索引 | 已实现 | MyBatis 读取根属性；iBATIS 同时索引声明的语句 id；只处理变化文件 |
| iBATIS 接口/XML | 已实现 | sqlMap、全名/唯一简称、自定义映射、无 namespace 文件名；接口与方法图标 |
| iBATIS 实现类导航 | 已实现 | 点击后按 Java 索引查找实现；读取静态 SQL id、常量拼接和同类辅助方法 |
| SQL API 字符串导航 | 已实现 | iBATIS SqlMapClient、Spring SqlMapClientTemplate、MyBatis SqlSession；存在多个候选时保留歧义 |
| iBATIS 旧参数/属性 | 基础支持 | #property#、$property$、JDBC 后缀、parameterClass/resultClass、parameterMap/resultMap class、动态 property 和 iterate |
| iBATIS 动态 SQL 求值 | 已实现 | dynamic、14 类条件标签、prepend/removeFirstPrepend、嵌套 iterate、include、内联参数；JSON 输入，离线显示 SQL 与参数 |
| iBATIS 数据库执行/迁移 | 基础执行/迁移未实现 | 主语句无跳过项时可在独立 JDBC 入口执行；OUT/INOUT、selectKey、自定义 TypeHandler 等仅预览；不自动迁移 XML |
| 参数补全 | 已实现 | Java、@Param、简单嵌套属性、集合、foreach；未知 Map 键不报错 |
| OGNL 补全与检查 | 基础兼容 | 真实 OGNL 语法树、已知属性/方法数量检查、零参数方法补全、String/Character 比较提示；不执行表达式，复杂类型/重载/返回值链/投影上下文未完整推导 |
| resultMap property | 已实现 | 字段/getter、association/collection 的基础类型解析；类型用全限定名 |
| resultMap column | 基础兼容 | SELECT 静态输出名、别名与 include；列补全、重复/缺失检查、内联 columnPrefix；通配符/动态投影跳过缺失判断，无数据库元数据 |
| 类型引用/别名 | 基础兼容 | 全限定名、内置别名、XML typeAliases、常量 properties/YAML 包配置、包内 @Alias 和手动设置；模块范围，歧义不选类；通配/环境变量/注册器未实现 |
| refid/resultMap | 已实现 | 本地定义及显式跨 namespace 引用导航、存在性检查 |
| 重构 | 已实现限定范围 | 属性、类名、Mapper 方法引用；@Param 绑定名、映射 id、foreach/bind 声明；保留作用域、namespace 前缀和显式绑定，完整 OGNL 方法名/类型重构未覆盖 |
| 映射检查 | 已实现 | 重复 id、无效引用/属性/参数、缺失 XML 方法；不是数据库语义验证 |
| DDL → CRUD | 已实现 | 单表 MySQL 常见 DDL；普通/复合主键；ANSI 标识符输出；预览并创建新文件 |
| MyBatis-Plus | 基础支持 | TableName/TableId/TableField、BaseMapper；复合主键拒绝生成 |
| 增量合并 | 未实现 | 同名文件阻止写入；不自动合并现有业务代码 |
| 方法名 → SQL | 基础支持 | 只生成查询；支持列名驼峰转换、比较、And/Or、IN、排序；未知方法/缺失参数报错 |
| 实体 → DDL | 已实现 | MySQL 类型默认值；复杂字段列出跳过项；约束需人工检查 |
| if test 快速操作 | 已实现 | 为选中的纯 SQL 片段加空值保护；复杂数组索引条件需检查 |
| 注解 SQL | 基础兼容 | 直接常量字符串参数补全/引用/检查/重构；常量数组/拼接/script 离线预览；script 编辑检查及参数/局部变量重构、Provider 求值、注解迁移未实现；script/外部常量/拼接注解方法拒绝自动 @Param 重构 |
| SQL 日志还原 | 已实现 | Preparing/Parameters 配对、字符串转义、null、数值、线程检查；日志未转义的歧义无法总是还原 |
| 控制台 SQL 链接 | 已实现 | 设置开启，按线程配对，点击 Parameters 行后台还原；不自动监听外部文件、不自动执行、不注入 Java Agent |
| 动态 SQL 预览 | 已实现限定范围 | MyBatis 使用原框架并在求值前检查离线边界；iBATIS 使用独立求值器；原预览不连接数据库，显示分支、参数和跳过项 |
| 测试用例生成 | 未实现 | 项目附带插件自身的测试与演示工程 |
| SQL 编辑/格式化 | 基础兼容 | XML/注解基础 SQL 关键字，XML 静态输出列补全；安装 IDEA Database 时为静态 SQL 注入 SQL 语言并使用平台提示/格式化；动态 SQL 的完整语言注入未实现 |
| JDBC/IDE Database | 基础兼容 | 用户配置本地驱动/连接，或读取 IDE 的常规 URL、驱动、凭证；独立连接，参数化 SELECT/INSERT/UPDATE/DELETE；查询/回滚/确认提交，未打包生产驱动 |
| 数据库表结构 | 已实现限定范围 | 精确数据源/schema，表/字段/JDBC 类型/主键；字段最多 2000，表列表最多 500；同名表不猜测，不把元数据读取当作 SQL 业务验证 |
| Kotlin | 基础兼容 | K2 轻量 Java 模型的 Mapper/XML、参数/属性、常量注解预览、直接 @Param 重构；suspend、插值、完整注解编辑/重构未实现 |
| Flex / tkmapper | 未实现 | 本次不扩展代码生成与其他框架专用能力 |
| Spring/MapperScan | 基础兼容 | 字面量扫描包/基准类、注解/标记接口过滤，Bean 名称和类型；标准 Boot 自动配置 @Mapper；动态值、元注解、自定义工厂/命名器等未完整支持 |
| MCP | 基础兼容 | IDEA 内置 HTTP 服务，项目令牌、本机 Origin/Host 校验；8 个非生成工具；可查询/回滚，不提供提交；JSON Streamable HTTP，协议 2025-03-26/2025-06-18 |
| 自定义 AI 提示词/Skill 配置 | 未实现 | 不自动修改 AI 客户端配置，不生成代码；连接窗口提供可复制的 JSON/TOML |

完整复刻原插件还需要数据库适配、其他语言接入、完整语义重构和 MCP 工程；上述剩余范围未在本版实现。

功能验证范围见 README 0.5.0，统一安装包的构建/验证范围见 README 0.5.1。剩余主要差距包括结合真实表结构的完整 SQL 语义检查、完整动态 SQL 编辑器、全部 Kotlin/Spring 场景、Java Agent、自定义 AI 提示词和代码生成扩展。数据库验证使用临时 H2，未连接用户的 MySQL/PostgreSQL/Oracle 等环境，不能据此声称业务 SQL 已验证。运行接入边界见 [runtime.md](runtime.md)。
