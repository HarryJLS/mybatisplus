package dev.mybatis.assistant.compat;

import dev.mybatis.assistant.core.SqlRenderer;
import com.ibatis.sqlmap.engine.builder.xml.*;
import com.ibatis.sqlmap.engine.mapping.parameter.*;
import com.ibatis.sqlmap.engine.mapping.statement.MappedStatement;
import com.ibatis.sqlmap.engine.scope.*;
import org.w3c.dom.*;
import java.util.*;

/** Uses the actual iBATIS 2 XMLSqlSource and DynamicSql handlers; never connects to a database. */
public final class IbatisOracleTests {
    private static int cases;
    private static String clean(String sql){return sql.replaceAll("\\s+"," ").trim();}
    @SuppressWarnings("unchecked")
    private static void verify(String body,String json)throws Exception{
        String xml="<sqlMap namespace='old'><select id='q'>"+body+"</select></sqlMap>";
        var ours=SqlRenderer.render(xml,"q",json,List.of());Object input=SqlRenderer.jsonValue(json);
        Document doc=SqlRenderer.parseXml(xml);Element statement=(Element)doc.getDocumentElement().getElementsByTagName("select").item(0);
        // Empty dynamic wrapper forces the runtime's mapping path without altering prepend policy.
        Element wrapper=doc.createElement("dynamic");while(statement.getFirstChild()!=null)wrapper.appendChild(statement.getFirstChild());statement.appendChild(wrapper);
        XmlParserState state=new XmlParserState();state.setNamespace("old");state.setUseStatementNamespaces(true);state.setGlobalProps(new Properties());
        var runtimeSql=new XMLSqlSource(state,statement).getSql();MappedStatement mapped=new MappedStatement();mapped.setId("old.q");mapped.setParameterClass(input==null?Object.class:input.getClass());mapped.setSql(runtimeSql);
        StatementScope scope=new StatementScope(new SessionScope());scope.setStatement(mapped);scope.setSql(runtimeSql);
        String actual=runtimeSql.getSql(scope,input);ParameterMap map=runtimeSql.getParameterMap(scope,input);
        var mappings=map.getParameterMappings();List<Object> params=new ArrayList<>();Object[] values=mappings.length==0?new Object[0]:map.getParameterObjectValues(scope,input);
        for(int i=0;i<mappings.length;i++){Object value=values[i];if(value!=null && mappings[i].getNullValue()!=null && mappings[i].getTypeHandler().equals(value,mappings[i].getNullValue()))value=null;params.add(value);}
        if(!clean(actual).equals(clean(ours.sql())) || !params.equals(ours.parameters()))throw new AssertionError("case "+cases+" input="+json+"\nruntime="+clean(actual)+" "+params+"\nours="+clean(ours.sql())+" "+ours.parameters());
        cases++;
    }
    public static void main(String[] args)throws Exception{
        String dynamic="SELECT * FROM users <dynamic prepend='WHERE'><isNotEmpty property='name' prepend='AND'> name=#name# </isNotEmpty><isGreaterEqual property='age' compareValue='18' prepend='AND'> age&gt;=#age# </isGreaterEqual></dynamic>";
        for(String input:List.of("{}","{\"name\":\"a\"}","{\"age\":20}","{\"name\":\"a\",\"age\":20}","{\"name\":\"\",\"age\":17}"))verify(dynamic,input);
        for(String tag:List.of("isEqual","isNotEqual","isGreaterThan","isGreaterEqual","isLessThan","isLessEqual"))for(String input:List.of("{\"value\":1}","{\"value\":2}","{\"value\":3}","{\"value\":null}"))verify("SELECT 1 <"+tag+" property='value' compareValue='2'> + #value# </"+tag+">",input);
        for(String tag:List.of("isNull","isNotNull","isEmpty","isNotEmpty"))for(String input:List.of("{}","{\"value\":null}","{\"value\":\"\"}","{\"value\":[]}","{\"value\":{}}","{\"value\":\"a\"}"))verify("SELECT 1 <"+tag+" property='value'> + 2 </"+tag+">",input);
        verify("<dynamic removeFirstPrepend='true'><isNotNull property='name' prepend='AND'> name=#name# </isNotNull></dynamic>","{\"name\":\"a\"}");
        verify("<isParameterPresent removeFirstPrepend='true'><isNotNull property='name' prepend='AND'> name=#name# </isNotNull><isNotNull property='age' prepend='AND'> age=#age# </isNotNull></isParameterPresent>","{\"name\":\"a\",\"age\":20}");
        verify("<iterate property='values' removeFirstPrepend='iterate' open='(' close=')' conjunction=' OR '><isParameterPresent prepend='AND'> #values[]# </isParameterPresent><isNotNull property='other' prepend='AND'> other=#other# </isNotNull></iterate>","{\"values\":[1,2],\"other\":3}");
        verify("<iterate property='values' removeFirstPrepend='true' open='(' close=')' conjunction=' OR '><isParameterPresent prepend='AND'> #values[]# </isParameterPresent></iterate>","{\"values\":[1,2]}");
        for(String input:List.of("{\"values\":[]}","{\"values\":[1,2,3]}","{\"values\":[1,null,2,null]}"))verify("SELECT 1 <iterate property='values' prepend='AND' open='(' close=')' conjunction=','><isNotNull property='values[]'> #values[]# </isNotNull></iterate>",input);
        verify("<iterate open='(' close=')' conjunction=','> #[].name# </iterate>","[{\"name\":\"a\"},{\"name\":\"b\"}]");
        verify("<iterate property='groups' conjunction=' OR '><iterate property='groups[].values' open='(' close=')' conjunction=','> #groups[].values[]# </iterate></iterate>","{\"groups\":[{\"values\":[1,2]},{\"values\":[3]}]}");
        verify("SELECT #value:INTEGER:-1#, #name,jdbcType=VARCHAR#","{\"value\":-1,\"name\":\"a\"}");
        verify("SELECT ##, $$, $order$","{\"order\":\"name DESC\"}");
        verify("SELECT # name #, $ order $","{\"name\":\"a\",\"order\":\"id\"}");
        verify("SELECT #whatever#","42");verify("<isParameterPresent>present</isParameterPresent><isNotParameterPresent>absent</isNotParameterPresent>","null");
        verify("<isLessThan property='low' compareProperty='high'>yes</isLessThan>","{\"low\":1,\"high\":2}");
        for(String input:List.of("{}","{\"name\":null}","{\"name\":\"a\"}"))verify("<isPropertyAvailable property='name'>available</isPropertyAvailable><isNotPropertyAvailable property='name'>missing</isNotPropertyAvailable>",input);
        System.out.println("PASS: "+cases+" actual iBATIS 2 differential cases (SQL and ordered parameters)");
    }
}
