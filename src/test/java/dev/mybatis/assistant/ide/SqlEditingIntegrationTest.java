package dev.mybatis.assistant.ide;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.*;

public final class SqlEditingIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private boolean previousAutocomplete;
    @Override protected void setUp()throws Exception{
        super.setUp();
        previousAutocomplete=com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION;com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION=false;
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Mapper {}");myFixture.addClass("package org.apache.ibatis.annotations; public @interface Param { String value(); }");
        myFixture.addClass("package java.lang; public class String { public int length(){return 0;} public boolean isEmpty(){return false;} public String trim(){return this;} }");
        myFixture.addClass("package java.lang; public class Long {}");
        myFixture.addClass("package java.lang; public interface Iterable<T> {}");
        myFixture.addClass("package java.util; public interface Collection<T> extends java.lang.Iterable<T> { int size(); boolean isEmpty(); }");
        myFixture.addClass("package java.util; public interface List<T> extends Collection<T> { T get(int index); }");
        myFixture.addClass("package java.util; public interface Map<K,V> { int size(); V get(Object key); Collection<V> values(); }");
        myFixture.addClass("package sample; public class Address { public String city; }");myFixture.addClass("package sample; public class Order { public Long id; public String title; }");
        myFixture.addClass("package sample; public class User { public Long id; public String name; public Address address; public java.util.List<Order> orders; public Address getAddress(){return address;} }");
        myFixture.addClass("package sample; public class Box<T> { public T get(){return null;} }");
        myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface EditorMapper { User find(@org.apache.ibatis.annotations.Param(\"user\") User u,@org.apache.ibatis.annotations.Param(\"users\") java.util.List<User> users,@org.apache.ibatis.annotations.Param(\"lookup\") java.util.Map<String,User> lookup,@org.apache.ibatis.annotations.Param(\"box\") Box<User> box); }");
    }
    @Override protected void tearDown()throws Exception{try{com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION=previousAutocomplete;}finally{super.tearDown();}}
    private XmlFile xml(String body){XmlFile file=(XmlFile)myFixture.addFileToProject("src/main/resources/mapper/Editor.xml","<mapper namespace='sample.EditorMapper'>"+body+"</mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());return file;}
    private boolean warning(String text){return myFixture.doHighlighting().stream().anyMatch(h->h.getDescription()!=null && h.getDescription().contains(text));}
    public void testMapKeyIndexAndForeachTypes(){
        XmlFile file=xml("<select id='find'><foreach collection='lookup' item='value' index='key'>#{value.name},#{key}</foreach></select>");XmlTag loop=file.getRootTag().findFirstSubTag("select").findFirstSubTag("foreach");ParameterModel model=ParameterModel.of(null,loop.getValue().getTextElements()[0]);
        assertEquals("java.lang.String",model.resolve("key").type().getCanonicalText());assertEquals("java.lang.String",model.resolve("lookup['a.b'].name").type().getCanonicalText());assertNotNull(model.resolve("value.address.city"));assertNull(model.resolve("value.missing"));
    }
    public void testNestedCollectionIndexesAndInvalidScalarIndex(){
        PsiClass mapper=myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface NestedMapper { User find(@org.apache.ibatis.annotations.Param(\"groups\") java.util.List<? extends java.util.List<User>> groups,@org.apache.ibatis.annotations.Param(\"user\") User user); }");ParameterModel model=ParameterModel.of(mapper.getMethods()[0],null);assertNotNull(model.resolve("groups[0][0].name"));assertNull(model.resolve("user[0].name"));
    }
    public void testForeachCollectionMethodReturnType(){
        XmlFile file=xml("<select id='find'><foreach collection='lookup.values()' item='value'>#{value.name}</foreach></select>");XmlTag loop=file.getRootTag().findFirstSubTag("select").findFirstSubTag("foreach");assertNotNull(ParameterModel.of(null,loop.getValue().getTextElements()[0]).resolve("value.name"));
    }
    public void testBindTypeAndDeclarationOrder(){
        XmlFile file=xml("<select id='find'><if test='future != null'>SELECT 1</if><bind name='text' value='user.name.trim()'/><bind name='length' value='text.length()'/><bind name='future' value='user'/><if test='length > 0'>WHERE name=#{text}</if></select>");XmlTag select=file.getRootTag().findFirstSubTag("select");
        assertNull(ParameterModel.of(null,select.findFirstSubTag("if").getAttribute("test")).resolve("future"));
        ParameterModel model=ParameterModel.of(null,select.findSubTags("if")[1].getAttribute("test"));assertEquals("java.lang.String",model.resolve("text").type().getCanonicalText());assertEquals("int",model.resolve("length").type().getCanonicalText());
    }
    public void testConditionalAndIncludedBindVisibility(){
        XmlFile file=xml("<sql id='bindings'><bind name='pattern' value='user.name.trim()'/></sql><select id='find'><if test='user != null'><bind name='optional' value='user'/></if><include refid='bindings'/>SELECT #{pattern},#{optional}</select>");XmlText text=file.getRootTag().findFirstSubTag("select").getValue().getTextElements()[0];ParameterModel model=ParameterModel.of(null,text);assertNotNull(model.resolve("optional"));assertNull(model.resolve("optional").type());assertEquals("java.lang.String",model.resolve("pattern").type().getCanonicalText());
    }
    public void testUnusedFragmentsAndOtherStatementsDoNotLeakBinds(){
        XmlFile file=xml("<sql id='unused'><bind name='leak' value='user'/></sql><select id='other'><bind name='other' value='user'/>SELECT 1</select><select id='find'><bind name='own' value='user'/>SELECT #{own.id}</select>");XmlText text=file.getRootTag().findSubTags("select")[1].getValue().getTextElements()[0];ParameterModel model=ParameterModel.of(null,text);assertNull(model.resolve("leak"));assertNull(model.resolve("other"));assertNotNull(model.resolve("own.id"));
    }
    public void testOgnlGetterAndGenericMethodChainInspection(){
        myFixture.enableInspections(new MapperInspection());myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.EditorMapper'><select id='find'><if test='box.get().getAddress().missing != null'>SELECT 1</if></select></mapper>");assertTrue(warning("找不到 OGNL 参数或属性: box.get().getAddress().missing"));
    }
    public void testCompletionAfterGenericMethodChain(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.EditorMapper'><select id='find'><if test='box.get().getAddress().ci<caret> != null'>SELECT 1</if></select></mapper>");var items=myFixture.completeBasic();assertNotNull(items);assertTrue(Arrays.stream(items).anyMatch(i->i.getLookupString().equals("city")));
    }
    public void testPlaceholderOptionsAndDollarExpressions(){
        myFixture.enableInspections(new MapperInspection());myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.EditorMapper'><select id='find'>SELECT #{user.id,jdbcType=NOT_A_TYPE}, #{user.id,mode=BAD} FROM users WHERE name LIKE '%#{user.name}%' ORDER BY ${user.missing}</select></mapper>");assertTrue(warning("无效 jdbcType"));assertTrue(warning("无效参数 mode"));assertTrue(warning("位于 SQL 引号内"));assertTrue(warning("找不到 OGNL 参数或属性: user.missing"));assertTrue(warning("直接拼接 SQL"));
    }
    public void testDynamicTagStructureAndCollectionInspection(){
        myFixture.enableInspections(new MapperInspection());myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.EditorMapper'><select id='find'><when test='true'>SELECT 1</when><if>SELECT 2</if><foreach collection='user.name' item='x' index='x'>#{x}</foreach></select></mapper>");assertTrue(warning("when 必须位于 choose"));assertTrue(warning("if 缺少 test"));assertTrue(warning("foreach collection 必须"));assertTrue(warning("item 和 index 不能同名"));
    }
    public void testNestedReferencedResultMapPrefixColumns(){
        XmlFile shared=(XmlFile)myFixture.addFileToProject("src/main/resources/mapper/Shared.xml","<mapper namespace='sample.Shared'><resultMap id='address' type='sample.Address'><result column='city' property='city'/></resultMap></mapper>");
        xml("<resultMap id='r' type='sample.User'><id column='id' property='id'/><association property='address' resultMap='sample.Shared.address' columnPrefix='a_'/></resultMap><select id='find' resultMap='r'>SELECT id, city AS a_city FROM users</select>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());assertEquals(List.of("city"),StatementSql.columns(shared.getRootTag().findFirstSubTag("resultMap")).columns());
    }
    public void testResultMapInheritedUsage(){
        XmlFile file=xml("<resultMap id='base' type='sample.User'><id column='id' property='id'/></resultMap><resultMap id='derived' type='sample.User' extends='base'><result column='name' property='name'/></resultMap><select id='find' resultMap='derived'>SELECT id, name FROM users</select>");assertTrue(StatementSql.columns(file.getRootTag().findFirstSubTag("resultMap")).complete());assertEquals(List.of("id","name"),StatementSql.columns(file.getRootTag().findFirstSubTag("resultMap")).columns());
    }
    public void testGenerateNestedResultMapAndRejectDuplicateAliases(){
        XmlFile file=xml("<select id='find' resultType='sample.User'>SELECT id, name, city AS address_city, order_id AS orders_id, title AS orders_title FROM users</select>");var plan=ResultMapEdits.generate(file.getRootTag().findFirstSubTag("select"));assertEquals(List.of(),plan.unmatched());XmlTag map=XmlElementFactory.getInstance(getProject()).createTagFromText(plan.xml());assertEquals("address_",map.findFirstSubTag("association").getAttributeValue("columnPrefix"));assertEquals("city",map.findFirstSubTag("association").findFirstSubTag("result").getAttributeValue("column"));assertEquals("orders_",map.findFirstSubTag("collection").getAttributeValue("columnPrefix"));
    }
    public void testResultMapInfersInheritedGenericReturnEntity(){
        myFixture.addClass("package sample; public interface ResultBase<T> { java.util.List<T> find(); }");myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface ResultMapper extends ResultBase<User> {}");XmlFile file=(XmlFile)myFixture.addFileToProject("src/main/resources/mapper/Result.xml","<mapper namespace='sample.ResultMapper'><select id='find'>SELECT id, name FROM users</select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());var plan=ResultMapEdits.generate(file.getRootTag().findFirstSubTag("select"));assertEquals(List.of(),plan.unmatched());assertTrue(plan.xml(),plan.xml().contains("type=\"sample.User\""));
    }
    public void testResultMapUnmatchedAndDynamicProjectionAreExplicit(){
        XmlFile file=xml("<select id='find' resultType='sample.User'>SELECT id, mystery FROM users</select>");assertEquals(List.of("mystery"),ResultMapEdits.generate(file.getRootTag().findFirstSubTag("select")).unmatched());
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),()->file.getRootTag().findFirstSubTag("select").getValue().setText("SELECT * FROM users"));try{ResultMapEdits.generate(file.getRootTag().findFirstSubTag("select"));fail("wildcard generation must fail");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("投影不完整"));}
    }
    public void testDynamicEditingKeepsChooseBranchAndWhereContext(){
        XmlFile file=xml("<select id='find'>SELECT id FROM users <where><choose><when test='user != null'>AND name=#{user.name}</when><otherwise>AND id=#{user.id}</otherwise></choose></where></select>");List<XmlText> texts=new ArrayList<>(PsiTreeUtil.findChildrenOfType(file,XmlText.class));
        for(XmlText text:texts)if(text.getValue().contains("AND name=") || text.getValue().contains("AND id=")){var places=DynamicSqlEditing.places(text);assertFalse(places.isEmpty());String sql=injected(text,places);assertTrue(sql,sql.contains("FROM users"));assertTrue(sql,sql.contains("WHERE"));assertFalse(sql,sql.contains("WHERE AND"));assertFalse(sql,sql.contains("#{"));}
    }
    public void testWhereRemovesConnectorsBeforeNewlineAndTab(){
        for(String gap:List.of(" ","\n","\r","\t")){
            XmlTag root=XmlElementFactory.getInstance(getProject()).createTagFromText("<mapper namespace='sample.EditorMapper'><select id='find'>SELECT id FROM users <where><if test='user != null'>AND"+gap+"name=#{user.name}</if></where></select></mapper>");XmlText text=root.findFirstSubTag("select").findFirstSubTag("where").findFirstSubTag("if").getValue().getTextElements()[0];String sql=injected(text,DynamicSqlEditing.places(text)).replaceAll("\\s+"," ");assertTrue(sql,sql.contains("WHERE name="));assertFalse(sql,sql.contains("WHERE AND"));
        }
    }
    public void testDynamicEditingSetTrimForeachAndInclude(){
        XmlFile file=xml("<sql id='table'>users</sql><update id='find'>UPDATE <include refid='table'/><set><if test='user != null'>name=#{user.name},</if></set> WHERE id IN <foreach collection='users' item='u' open='(' close=')' separator=','>#{u.id}</foreach></update>");
        XmlText text=PsiTreeUtil.findChildrenOfType(file,XmlText.class).stream().filter(t->t.getValue().contains("name=")).findFirst().orElseThrow();var places=DynamicSqlEditing.places(text);assertFalse(places.isEmpty());String sql=injected(text,places);assertTrue(sql,sql.contains("UPDATE"));assertTrue(sql,sql.contains("users"));assertFalse(sql,sql.contains(", WHERE"));assertTrue(sql,sql.contains("SET"));
    }
    public void testSqlFragmentParameterCompletionUsesConsumers(){
        XmlFile file=xml("<sql id='condition'>AND name=#{user.name}</sql><select id='find'>SELECT 1 <include refid='condition'/></select>");XmlText text=file.getRootTag().findFirstSubTag("sql").getValue().getTextElements()[0];assertNotNull(ParameterModel.of(null,text).resolve("user.name"));
    }
    public void testSharedSqlEditingKeepsConsumerTableContext(){
        XmlFile file=xml("<sql id='columns'>id, name</sql><select id='find'>SELECT <include refid='columns'/> FROM users</select>");XmlText text=file.getRootTag().findFirstSubTag("sql").getValue().getTextElements()[0];String sql=injected(text,DynamicSqlEditing.places(text));assertTrue(sql,sql.contains("SELECT"));assertTrue(sql,sql.contains("FROM users"));assertFalse(sql,sql.contains("__mybatis_fragment"));
    }
    public void testSelectKeyEditingIsIndependentFromInsert(){
        XmlFile file=xml("<insert id='find'>INSERT INTO users(name) VALUES(#{user.name})<selectKey keyProperty='user.id' order='AFTER' resultType='long'>SELECT LAST_INSERT_ID()</selectKey></insert>");XmlText text=file.getRootTag().findFirstSubTag("insert").findFirstSubTag("selectKey").getValue().getTextElements()[0];String sql=injected(text,DynamicSqlEditing.places(text));assertTrue(sql,sql.contains("SELECT LAST_INSERT_ID()"));assertFalse(sql,sql.contains("INSERT INTO"));
    }
    private static String injected(XmlText text,List<DynamicSqlEditing.Place> places){StringBuilder sql=new StringBuilder();for(var place:places)sql.append(place.prefix()).append(place.range().substring(text.getText())).append(place.suffix());return sql.toString();}
    public void testMapperMethodSnapshotAndTestEntrypoints(){
        XmlFile file=xml("<select id='find'>SELECT #{user.id}</select>");PsiMethod method=MapperModel.method(file.getRootTag().findFirstSubTag("select"));var input=MapperSnapshot.forMethod(method,"{\"user\":{\"id\":3}}");assertEquals("find",input.statementId());assertTrue(input.xml().contains("SELECT #{user.id}"));
        assertTrue(new GenerateMapperTestIntention().isAvailable(getProject(),null,method));assertTrue(new GenerateResultMapIntention().isAvailable(getProject(),null,file.getRootTag().findFirstSubTag("select")));
    }
    public void testCaretIfActionProtectsListIndex(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.EditorMapper'><select id='find'>SELECT id FROM users WHERE 1=1\nAND name=#{users[0].na<caret>me}\n</select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());WrapIfIntention action=new WrapIfIntention();assertTrue(action.isAvailable(getProject(),myFixture.getEditor(),myFixture.getFile()));myFixture.launchAction(action);String text=myFixture.getFile().getText();assertTrue(text,text.contains("users.size &gt; 0"));assertTrue(text,text.contains("users[0].name != null"));
    }
    public void testGeneratedFilesNeverOverwriteExistingTest()throws Exception{
        java.nio.file.Path base=java.nio.file.Files.createTempDirectory("mapper-existing-test");String javaPath="src/test/java/sample/ExistingTest.java",configPath="src/test/resources/existing.xml";java.nio.file.Path existing=base.resolve(javaPath);java.nio.file.Files.createDirectories(existing.getParent());java.nio.file.Files.writeString(existing,"keep my test");com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(base.toString());
        try{try{GenerateMapperTestIntention.write(getProject(),base.toString(),new dev.mybatis.assistant.core.MapperTestGenerator.Files(javaPath,"overwrite",configPath,"config"));fail("existing test must reject the whole write");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("不覆盖"));}assertEquals("keep my test",java.nio.file.Files.readString(existing));assertFalse(java.nio.file.Files.exists(base.resolve(configPath)));}
        finally{try(var paths=java.nio.file.Files.walk(base)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
    }
}
