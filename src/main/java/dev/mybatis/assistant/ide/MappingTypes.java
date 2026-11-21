package dev.mybatis.assistant.ide;

import com.intellij.psi.*;
import com.intellij.psi.xml.XmlTag;

final class MappingTypes {
    private MappingTypes(){}
    static PsiType ownerType(XmlTag tag){
        XmlTag parent=tag.getParentTag();if(parent==null)return null;
        if("resultMap".equals(parent.getName()) || "parameterMap".equals(parent.getName()))return named(parent,MapperModel.isLegacy(MapperModel.root(parent))?parent.getAttributeValue("class"):parent.getAttributeValue("type"));
        if("association".equals(parent.getName()) || "collection".equals(parent.getName())){
            String name=parent.getAttributeValue("ofType");if(name==null)name=parent.getAttributeValue("javaType");
            if(name!=null)return named(parent,name);
            ParameterModel.Symbol s=ParameterModel.properties(ownerType(parent)).get(parent.getAttributeValue("property"));
            return s==null?null:"collection".equals(parent.getName())?MapperModel.entityType(s.type()):s.type();
        }return ownerType(parent);
    }
    static PsiType named(XmlTag tag,String name){
        PsiClass cls=MapperModel.typeClass(tag,name);
        return cls==null?null:JavaPsiFacade.getElementFactory(tag.getProject()).createType(cls);
    }
}
