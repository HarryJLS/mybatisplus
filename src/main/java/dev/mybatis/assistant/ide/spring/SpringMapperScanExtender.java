package dev.mybatis.assistant.ide.spring;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.spring.model.SpringImplicitBean;
import com.intellij.spring.model.custom.ComponentScanExtender;
import com.intellij.spring.model.jam.stereotype.SpringStereotypeElement;
import dev.mybatis.assistant.ide.*;
import java.beans.Introspector;
import java.util.*;

/** Spring supplies its actual component-scan scope; Mapper discovery uses annotation indexes. */
public final class SpringMapperScanExtender extends ComponentScanExtender {
    @Override public Collection<? extends SpringStereotypeElement> getComponents(GlobalSearchScope scope,Module module){
        if(module==null || DumbService.isDumb(module.getProject()) || !AssistantSettings.get(module.getProject()).springIntegration)return List.of();
        PsiClass annotation=MapperModel.classByName(module.getProject(),"org.apache.ibatis.annotations.Mapper");
        if(annotation==null || MapperModel.classByName(module.getProject(),"org.mybatis.spring.SqlSessionFactoryBean")==null)return List.of();
        if(MapperModel.classByName(module.getProject(),"org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration")==null && MapperModel.classByName(module.getProject(),"com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")==null)return List.of();
        var moduleScope=GlobalSearchScope.moduleWithDependenciesScope(module);
        boolean boot=false;
        for(String name:List.of("org.springframework.boot.autoconfigure.SpringBootApplication","org.springframework.boot.autoconfigure.EnableAutoConfiguration")){
            PsiClass marker=MapperModel.classByName(module.getProject(),name);if(marker!=null && AnnotatedElementsSearch.searchPsiClasses(marker,moduleScope).findFirst()!=null){boot=true;break;}
        }
        if(!boot)return List.of();
        for(String name:List.of("org.mybatis.spring.annotation.MapperScan","org.mybatis.spring.annotation.MapperScans")){
            PsiClass marker=MapperModel.classByName(module.getProject(),name);if(marker!=null && AnnotatedElementsSearch.searchPsiClasses(marker,moduleScope).findFirst()!=null)return List.of();
        }
        List<SpringStereotypeElement> result=new ArrayList<>();
        AnnotatedElementsSearch.searchPsiClasses(annotation,scope).forEach(cls->{
            if(result.size()>=4096)return false;if(cls.isInterface())result.add(SpringImplicitBean.create("MyBatis Assistant @Mapper",cls,Introspector.decapitalize(cls.getName())));return true;
        });return result;
    }
}
