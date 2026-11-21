package dev.mybatis.assistant.ide;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlAttribute;
import dev.mybatis.assistant.core.OgnlSyntax;
import java.util.*;
import org.apache.ibatis.ognl.*;

final class OgnlChecks {
    private OgnlChecks() {}
    static void inspect(XmlAttribute attribute, ParameterModel model, ProblemsHolder holder) {
        if (attribute.getValue() == null || attribute.getValueElement() == null) return;
        try { walk(OgnlSyntax.parse(com.intellij.openapi.util.text.StringUtil.unescapeXmlEntities(attribute.getValue())),model,attribute,holder); }
        catch (OgnlException e) {
            holder.registerProblem(attribute.getValueElement(),"OGNL 语法错误或表达式超出上限");
        }
    }
    private static void walk(Node node, ParameterModel model, XmlAttribute host, ProblemsHolder holder) {
        if (node instanceof ASTChain chain) {
            String path = "";
            for (int i = 0; i < chain.jjtGetNumChildren(); i++) {
                Node child = chain.jjtGetChild(i);
                if (child instanceof ASTProperty prop) {
                    Object value = constant(prop);
                    if (value == null) { path = ""; continue; }
                    if (prop.isIndexedAccess()) path += "["+value+"]";
                    else path += (path.isEmpty()?"":".")+value;
                    checkPath(path,model,host,holder);
                } else if (child instanceof ASTMethod method) {
                    checkMethod(path,method,model,host,holder);
                    for (int j=0;j<method.jjtGetNumChildren();j++) walk(method.jjtGetChild(j),model,host,holder);
                    // Return types and OGNL projection/lambda contexts are deliberately not guessed.
                    return;
                } else return;
            }
            return;
        }
        if (node instanceof ASTProperty prop && !prop.isIndexedAccess()) {
            Object value=constant(prop); if(value instanceof String path)checkPath(path,model,host,holder);
            return;
        }
        if ((node instanceof ASTEq || node instanceof ASTNotEq) && node.jjtGetNumChildren()==2) {
            for (int i=0;i<2;i++) if (node.jjtGetChild(i) instanceof ASTConst c && c.getValue() instanceof Character) {
                Node other=node.jjtGetChild(1-i);
                ParameterModel.Symbol symbol=model.resolve(other.toString());
                if(symbol!=null && symbol.type()!=null && symbol.type().equalsToText("java.lang.String"))
                    holder.registerProblem(host.getValueElement(),"OGNL 单字符单引号是 Character；比较 String 请使用双引号");
            }
        }
        for(int i=0;i<node.jjtGetNumChildren();i++)walk(node.jjtGetChild(i),model,host,holder);
    }
    private static Object constant(ASTProperty prop) {
        return prop.jjtGetNumChildren()==1 && prop.jjtGetChild(0) instanceof ASTConst c ? c.getValue() : null;
    }
    private static void checkPath(String path,ParameterModel model,XmlAttribute host,ProblemsHolder holder) {
        if(path.isBlank() || Set.of("_databaseId","_parameter","this","root").contains(path) || model.dynamic())return;
        if(model.resolve(path)==null)holder.registerProblem(host.getValueElement(),"找不到 OGNL 参数或属性: "+path);
    }
    private static void checkMethod(String path,ASTMethod method,ParameterModel model,XmlAttribute host,ProblemsHolder holder) {
        if(path.isEmpty())return;
        ParameterModel.Symbol symbol=model.resolve(path);
        if(symbol==null || !(symbol.type() instanceof PsiClassType type) || ParameterModel.isMap(type))return;
        PsiClass cls=type.resolve();if(cls==null)return;
        int args=method.jjtGetNumChildren();
        for(PsiMethod candidate:cls.findMethodsByName(method.getMethodName(),true)) {
            int count=candidate.getParameterList().getParametersCount();
            if(candidate.hasModifierProperty(PsiModifier.PUBLIC) && (count==args || candidate.isVarArgs() && args>=count-1))return;
        }
        holder.registerProblem(host.getValueElement(),"找不到 OGNL 方法或参数数量不符: "+path+"."+method.getMethodName()+"()");
    }
}
