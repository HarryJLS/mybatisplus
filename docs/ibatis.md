# iBATIS 2 兼容说明

0.2.0 新增 **DAO 接口 → sqlMap XML** 导航；0.3.0 加入旧动态 SQL 离线求值。原有 MyBatis 3 和 MyBatis-Plus 能力保留。

## 接口与 XML 跳转

接口名旁的图标跳到整个 XML；方法名旁的图标跳到匹配的语句。XML 的语句 id 可以跳回接口方法。

自动关联以下情况：

| XML 写法 | 接口关联方式 |
| --- | --- |
| `<sqlMap namespace="com.example.UserDao">` | 精确接口全名 |
| `<sqlMap namespace="UserDao">` | 项目内唯一的同名接口 |
| `UserDao.xml` 中的 `<sqlMap>` 没有 namespace | 项目内唯一的同名接口 |
| `id="findUser"` 或 `id="UserDao.findUser"` | 与对应接口的方法名关联 |

多个包存在同名接口时，简称不会自动选择其中一个。自定义 namespace 可在 **Settings → Tools → MyBatis Assistant** 配置，每行一条：

```text
com.example.dao.UserDao=legacy.Users
com.example.dao.OrderDao=Sales.Orders
```

没有 namespace 时，右侧填 XML 文件名（不含 `.xml`）。显式映射只建立关联，不会修改原 XML。接口重命名后需要同步修改显式映射的左侧。

## 方法名与 SQL id 不同时

iBATIS DAO 通常自己调用 SQL，接口方法名不必与 SQL id 相同。例如：

```java
public User getUser(Long id) throws SQLException {
    return (User) sqlMapClient.queryForObject("legacy.Users.load", id);
}
```

把光标放在 DAO 接口或实现方法上，选择 **Tools → MyBatis Assistant → 从 DAO 接口查找 iBATIS XML**。已有 sqlMap 关联但找不到同名语句时，方法旁的查找图标也会触发这个操作。

该操作通过 IDEA 的 Java 索引定位实现，然后读取实现中的静态语句 id；支持常量拼接及同一个实现类中的辅助方法。查找在后台按需运行，最多 128 个实现、8 层辅助方法。运行时拼装的 id、跨类业务调用链和依赖注入的运行时分派不会被推断为确定结果。

## SQL 客户端字符串

可从以下已知 API 的完整语句字符串跳转到 XML：

- `SqlMapClient` / `SqlMapSession` / `SqlMapExecutor` 的 queryForObject、queryForList、queryForMap、insert、update、delete 等。
- Spring 的 `SqlMapClientTemplate` / `SqlMapClientOperations`。
- MyBatis 的 `SqlSession.selectOne/selectList/selectMap/selectCursor` 等，使用完整 `namespace.id`。

iBATIS 的裸语句 id 也会通过持久索引查找。多个 sqlMap 声明相同裸 id 时会保留多个候选；不会随意绑定。普通业务字符串不会作为 SQL id 处理。

## 参数与旧 XML

- `parameterClass`、`resultClass`、`resultMap class`、`parameterMap class` 和 sqlMap 内的 `typeAlias`。
- `#name#`、`#name:VARCHAR#`、`$name$` 的属性引用与基础补全。
- isEqual、isNotEqual、isNull、isNotNull、isEmpty、isNotEmpty、比较标签、属性存在性标签与 iterate 的 `property`。
- `#items[].name#` 与无 property 的 iterate 内 `#[].name#` 的基础元素类型解析。
- iBATIS DAO 不执行 MyBatis 代理的“每个接口方法必须同名 XML”检查，也不把 MyBatis 的 @Param/paramN 绑定规则套到旧参数对象上。

全局 sqlMapConfig 的别名扫描、数据库验证、iBATIS 原始日志格式和自动迁移未实现。未知参数类型或 Map 键会避免严格误报；这不等于完成了运行时验证。

## 生成与预览边界

缺失语句的 XML 骨架使用 `resultClass`。方法名 SQL 生成和 if-test 插入仍采用 MyBatis 3 语法，在 iBATIS 上不会自动插入这些内容。

## 旧动态 SQL 求值（0.3.0）

保持 sqlMap XML 为当前编辑器，把光标放在目标语句内，从 Tools → MyBatis Assistant → 动态 SQL 预览打开窗口，输入 JSON 后预览。也可手动填写语句 id。

支持：

- `dynamic`、`prepend`、`open` / `close` 和 `removeFirstPrepend=true/false/iterate`。
- isNull/isNotNull、isEmpty/isNotEmpty、isEqual/isNotEqual、isGreaterThan/isGreaterEqual、isLessThan/isLessEqual、isPropertyAvailable/isNotPropertyAvailable、isParameterPresent/isNotParameterPresent。
- `compareValue` / `compareProperty`、空分支、首个连接词移除、嵌套 iterate 与集合元素筛选。
- `#name#`、`#name:VARCHAR#`、逗号参数选项、`nullValue`、`$name$` 及 `##` / `$$` 转义。
- 本地或显式跨 namespace 的 `include`，并拒绝循环/歧义引用。
- 静态 SQL 的外置 `parameterMap`，验证 ? 与参数数量；OUT/INOUT 占位会说明输出值尚待数据库返回。

例如 `examples/ibatis` 的 `search` 输入 `{"name":"张三"}` 可得到带 WHERE 的 SQL 和参数；输入 `{"name":""}` 时不会输出空 WHERE。新增 `findByIds` 用 `{"ids":[1,2,3]}` 预览迭代，用 `{"ids":[]}` 查看空集合分支。

JSON 对象按 Java Map 语义处理，数组按 List 处理，数字按 BigDecimal 处理；不按照 parameterClass 实例化业务对象或日期对象。isPropertyAvailable 对 Map 检查直接键：`{"user":{"name":"a"}}` 不等于存在直接键 `user.name`。空 Map 与空集合的 isEmpty 行为也不同，按旧框架规则处理。

旧占位符不会自动去除名称中的空格：`# name #` 引用的是键 ` name `，不是 `name`；参数选项采用 iBATIS 的无额外空格写法。

求值显示预编译 SQL、有序参数及范围说明。`$…$` 是原样拼接；`selectKey` 是另一条查询，本次只渲染主语句并注明跳过。自定义 TypeHandler、Java 对象构造、集合/对象排序比较及动态标签混用外置 parameterMap 明确不支持，不以近似结果冒充运行时绑定。未知标签、错误索引、未闭合参数与超出上限会报错。

上限：XML 4 MiB、JSON 1 MiB、关联 sqlMap 64 个、嵌套 64 层、单次 iterate 10000 个元素、参数 10000 个、展开步骤 100000、SQL 输出 4 MiB。

插件不打包或启动 iBATIS 运行时，也不会执行旧 SQL。真实 iBATIS 2 只在 `python3 scripts/test_legacy.py` 的测试进程中对照 SQL 和参数。示例在 `examples/ibatis`。
