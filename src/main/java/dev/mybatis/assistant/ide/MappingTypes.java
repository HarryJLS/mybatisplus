package dev.mybatis.assistant.ide;

import com.intellij.psi.*;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.util.PsiTreeUtil;

final class MappingTypes {
    private MappingTypes(){}
    static PsiType ownerType(XmlTag tag){
        XmlTag parent=tag.getParentTag();if(parent==null)return null;
        if("resultMap".equals(parent.getName()) || "parameterMap".equals(parent.getName()))return named(parent,MapperModel.isLegacy(MapperModel.root(parent))?parent.getAttributeValue("class"):parent.getAttributeValue("type"));
        if("association".equals(parent.getName()) || "collection".equals(parent.getName())){
            String name=parent.getAttributeValue("ofType");if(name==null)name=parent.getAttributeValue("javaType");
            if(name!=null)return named(parent,name);
            XmlTag referenced=MapperModel.definition(parent,parent.getAttributeValue("resultMap"),"resultMap");if(referenced!=null && referenced.getAttributeValue("type")!=null)return named(referenced,referenced.getAttributeValue("type"));
            ParameterModel.Symbol s=property(ownerType(parent),parent.getAttributeValue("property"));
            return s==null?null:"collection".equals(parent.getName())?ParameterModel.elementType(s.type()):s.type();
        }return ownerType(parent);
    }
    static ParameterModel.Symbol property(PsiType type,String path){
        if(path==null)return null;ParameterModel.Symbol symbol=null;
        for(String part:path.split("\\.")){symbol=ParameterModel.member(type,part);if(symbol==null)return null;type=symbol.type();}return symbol;
    }
    static boolean inSql(PsiElement element){for(XmlTag tag=PsiTreeUtil.getParentOfType(element,XmlTag.class,false);tag!=null;tag=tag.getParentTag())if(tag.getName().equals("sql"))return true;return false;}
    static PsiType named(XmlTag tag,String name){
        PsiClass cls=MapperModel.typeClass(tag,name);
        return cls==null?null:JavaPsiFacade.getElementFactory(tag.getProject()).createType(cls);
    }
}
