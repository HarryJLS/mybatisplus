# iBATIS 跳转示例

在 IDEA 中导入此目录的 Maven 工程。这里没有数据库配置，不运行 SQL。

1. 在 UserDao.getUser 上选择 Tools → MyBatis Assistant → 从 DAO 接口查找 iBATIS XML，通过实现找到 `legacy.Users.load`。
2. 在实现类 `queryForObject("legacy.Users.load", id)` 的字符串上跳到 XML。
3. 配置 `example.legacy.UserDao=legacy.Users` 后，接口名旁会显示 XML 图标；search 方法可以直接双向跳转。
4. 在旧格式 `#name#` 和动态标签 property 中体验补全；在 resultMap property 上跳到 User 属性。

5. 把光标放在 search 的 XML 内，从动态 SQL 预览输入 `{"name":"张三"}` 与 `{"name":""}` 比较 WHERE 分支。
6. findByIds 使用 `{"ids":[1,2,3]}` 查看 iterate 参数；使用 `{"ids":[]}` 查看 `1 = 0` 分支。

0.3.0 使用独立求值器处理旧动态 SQL；不调用 MyBatis 3 引擎，不连接数据库。
