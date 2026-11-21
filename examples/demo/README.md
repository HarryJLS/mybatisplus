# 体验示例

在 IDEA 中打开此目录的 `pom.xml` 并导入 Maven 项目。

- 在 `UserMapper.java` 的方法旁点击跳转图标，查看 XML。
- 在 XML 中输入 `#{na` 或 `if test="na`，查看实体属性补全。
- 在 `property="name"`、`refid="UserColumns"` 和方法 id 上使用跳转到声明。
- 将光标放在 `search` 内，打开 Tools → MyBatis Assistant → 动态 SQL 预览，输入 `{"name":"张三","age":18}`。
- 将光标放在 `findByIds` 内，使用 `{"ids":[1,2,3]}` 和 `{"ids":[]}` 分别预览两个分支。

该示例没有数据库连接，不会执行 SQL。
