# 原插件资源包分析

分析对象：`MyBatisCodeHelper-Pro/lib/instrumented-MyBatisCodeHelper-Pro241-3.6.9+2321.jar`。

已读取 JAR 目录、`META-INF/*.xml` 扩展声明、生成器配置字段、JDBC 配置结构以及模板变量名称，作为功能结构参考。原资源包仍保留在用户提供的目录；当前工程的源码、图标和生成逻辑是独立实现。

## 可确认的结构

- 原插件版本：`3.6.9+2321`；主包 1,928 个 `.class`，130 个非类资源。
- 核心 `plugin.xml` 声明 110 个扩展；Database 声明 25 个；Spring 声明 4 个；Kotlin 声明 8 个。
- 原插件使用 PSI 引用、补全、行标记、检查、DOM 模型、文件索引、语言注入、重构、工具窗口和设置页。
- 资源包含中英文文案、图标、Live Templates、FreeMarker/Velocity 模板、测试模板、MyBatis-Plus/Flex 服务模板和 MCP 文档资源。
- 依赖包括多个 JDBC 驱动、Druid、JSqlParser、FreeMarker、Velocity 相关资源、Kotlin、Janino、HTTP 客户端等。这说明完整功能不只是 XML 与 Java 文件跳转。

多数原类名已经混淆，资源清单只能说明存在的能力和接口结构，不能据此声称掌握全部内部算法或性能行为。

## 对本次实现的影响

1. 保留 namespace 文件索引、PSI 参数类型解析和工具窗口等符合 IDEA 工作方式的结构。
2. Rust 只负责独立文本解析，通过 JNI 同进程调用，避免每次补全启动进程。
3. 当前不打包数据库驱动、HTTP 客户端、Kotlin 与多套模板/SQL 解析库，控制首版体积与常驻资源。
4. 生成操作提供预览；同名文件阻止写入。原插件的增量合并并未在本版模拟为已经完成。
5. 未增加启动阶段扫描、MCP 服务器、SQL Agent 或后台轮询。

可以重新生成结构清单：

```sh
python3 scripts/inspect_reference.py
```

输出：[reference-inventory.json](reference-inventory.json)。它记录声明类别、资源路径、模板变量和依赖大小，便于后续按功能扩展。
