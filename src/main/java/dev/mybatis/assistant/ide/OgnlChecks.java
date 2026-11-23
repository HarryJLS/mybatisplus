package dev.mybatis.assistant.ide;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.util.*;
import com.intellij.openapi.util.Key;
import dev.mybatis.assistant.core.OgnlSyntax;
import java.util.*;
import org.apache.ibatis.ognl.*;

final class OgnlChecks {
    private OgnlChecks() {}
    private record Syntax(Node node,boolean invalid) {}
    private static final Key<CachedValue<Syntax>> SYNTAX=Key.create("mybatis.assistant.ognl.syntax");
    static void inspect(XmlAttribute attribute, ParameterModel model, ProblemsHolder holder) {
        if (attribute.getValue() == null || attribute.getValueElement() == null) return;
        Syntax syntax=CachedValuesManager.getManager(attribute.getProject()).getCachedValue(attribute,SYNTAX,()->{
            Syntax parsed;
            try{parsed=new Syntax(OgnlSyntax.parse(com.intellij.openapi.util.text.StringUtil.unescapeXmlEntities(attribute.getValue())),false);}
            catch(OgnlException e){parsed=new Syntax(null,true);}
            return CachedValueProvider.Result.create(parsed,attribute.getContainingFile());
        },false);
        if(syntax.invalid()) {
            holder.registerProblem(attribute.getValueElement(),"OGNL 语法错误或表达式超出上限");
        }else walk(syntax.node(),model,attribute,holder);
    }
    private static void walk(Node node, ParameterModel model, XmlAttribute host, ProblemsHolder holder) {
        Set<String> seen=new LinkedHashSet<>();
        OgnlTypes.infer(node,model,host,message->{
            if(seen.add(message))holder.registerProblem(host.getValueElement(),message);
        },0);
    }
}
