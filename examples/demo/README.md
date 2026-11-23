# 体验示例

在 IDEA 中打开此目录的 `pom.xml` 并导入 Maven 项目。

- 在 `UserMapper.java` 的方法旁点击跳转图标，查看 XML。
- 在 XML 中输入 `#{na` 或 `if test="na`，查看实体属性补全。
- 在 `property="name"`、`refid="UserColumns"` 和方法 id 上使用跳转到声明。
- 将光标放在 `search` 内，打开 Tools → MyBatis Assistant → 动态 SQL 预览，输入 `{"name":"张三","age":18}`。
- 将光标放在 `findByIds` 内，使用 `{"ids":[1,2,3]}` 和 `{"ids":[]}` 分别预览两个分支。

该示例没有数据库连接，不会执行 SQL。

## SQL 编辑与测试（0.5.8）

导入示例后，点击 Mapper 方法名左侧绿色按钮 → 测试 Mapper SQL / 生成 testcase，修改 JSON、查看渲染结果，再预览 JUnit 测试与独立 MyBatis 配置。示例的测试依赖需由项目提供；生成不自动修改 Maven 配置。默认测试只校验 SQL/参数；连接数据库的测试需要显式开启并配置测试 JDBC，结束回滚。

在使用实体 resultType 且 SELECT 字段/别名完整的语句中，点击 SELECT 开始行左侧绿色按钮 → 从 SELECT 生成 resultMap。通配符需先改为明确投影；生成预览不会覆盖现有 resultMap。选中含 #{} 的 SQL 条件或在 AND/OR 条件行内使用 Alt+Enter，可添加 if test 及嵌套/索引保护。
