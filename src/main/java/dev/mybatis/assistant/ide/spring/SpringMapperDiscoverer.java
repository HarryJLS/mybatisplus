package dev.mybatis.assistant.ide.spring;

import com.intellij.psi.PsiClass;
import com.intellij.spring.contexts.model.LocalModel;
import com.intellij.spring.model.*;
import com.intellij.spring.model.custom.CustomLocalComponentsDiscoverer;
import dev.mybatis.assistant.ide.SpringMapperModel;
import java.beans.Introspector;
import java.util.*;

public final class SpringMapperDiscoverer extends CustomLocalComponentsDiscoverer {
    @Override public Collection<CommonSpringBean> getCustomComponents(LocalModel<?> model){
        if(!(model.getConfig() instanceof PsiClass config))return List.of();List<CommonSpringBean> beans=new ArrayList<>();
        for(PsiClass mapper:SpringMapperModel.mappers(config))beans.add(SpringImplicitBean.create("MyBatis Assistant MapperScan",mapper,Introspector.decapitalize(mapper.getName())));return beans;
    }
}
