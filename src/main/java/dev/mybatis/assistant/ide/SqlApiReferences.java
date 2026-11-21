package dev.mybatis.assistant.ide;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.ProcessingContext;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Only statement-id arguments of known iBATIS/MyBatis SQL APIs receive references. */
public final class SqlApiReferences extends PsiReferenceContributor {
    private static final Set<String> METHODS=Set.of("queryForObject","queryForList","queryForMap","queryForPaginatedList","insert","update","delete","selectOne","selectList","selectMap","selectCursor");
    private static final Set<String> TYPES=Set.of("com.ibatis.sqlmap.client.SqlMapExecutor","com.ibatis.sqlmap.client.SqlMapClient","com.ibatis.sqlmap.client.SqlMapSession","org.springframework.orm.ibatis.SqlMapClientOperations","org.springframework.orm.ibatis.SqlMapClientTemplate","org.apache.ibatis.session.SqlSession");
    private static final com.intellij.openapi.util.Key<CachedValue<List<XmlTarget>>> TARGET_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.sql.api.targets");
    private record XmlTarget(SmartPsiElementPointer<PsiElement> pointer){}
    public static boolean isStatementArgument(PsiLiteralExpression literal){
        if(!(literal.getValue() instanceof String) || !(literal.getParent() instanceof PsiExpressionList args) || !(args.getParent() instanceof PsiMethodCallExpression call) || args.getExpressions().length==0 || args.getExpressions()[0]!=literal)return false;
        return isSqlCall(call);
    }
    public static boolean isSqlCall(PsiMethodCallExpression call){
        String name=call.getMethodExpression().getReferenceName();if(name==null || !METHODS.contains(name))return false;
        PsiExpression qualifier=call.getMethodExpression().getQualifierExpression();
        if(qualifier!=null && qualifier.getType() instanceof PsiClassType type){
            if(TYPES.contains(type.rawType().getCanonicalText()))return true;
            for(String api:TYPES)if(InheritanceUtil.isInheritor(type,api))return true;
        }
        PsiMethod resolved=call.resolveMethod();PsiClass owner=resolved==null?null:resolved.getContainingClass();
        return owner!=null && owner.getQualifiedName()!=null && TYPES.contains(owner.getQualifiedName());
    }
    @Override public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar){
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PsiLiteralExpression.class),new PsiReferenceProvider(){
            @Override public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement e,@NotNull ProcessingContext context){
                PsiLiteralExpression literal=(PsiLiteralExpression)e;
                if(DumbService.isDumb(e.getProject()) || !isStatementArgument(literal))return PsiReference.EMPTY_ARRAY;
                return new PsiReference[]{new StatementReference(literal,ElementManipulators.getValueTextRange(literal))};
            }
        });
    }
    private static final class StatementReference extends PsiPolyVariantReferenceBase<PsiLiteralExpression> {
        StatementReference(PsiLiteralExpression literal,TextRange range){super(literal,range,true);}
        @Override public ResolveResult @NotNull [] multiResolve(boolean incompleteCode){
            List<XmlTarget> targets=CachedValuesManager.getManager(myElement.getProject()).getCachedValue(myElement,TARGET_CACHE,()->{
                String id=(String)myElement.getValue();List<XmlTarget> result=new ArrayList<>();
                if(id!=null)for(var tag:MapperModel.sqlTargets(myElement.getProject(),id))result.add(new XmlTarget(SmartPointerManager.getInstance(myElement.getProject()).createSmartPsiElementPointer(tag.getAttribute("id").getValueElement())));
                return CachedValueProvider.Result.create(result,myElement.getContainingFile(),PsiModificationTracker.MODIFICATION_COUNT);
            },false);
            return targets.stream().map(t->t.pointer().getElement()).filter(Objects::nonNull).map(PsiElementResolveResult::new).toArray(ResolveResult[]::new);
        }
        @Override public Object @NotNull [] getVariants(){return EMPTY_ARRAY;}
        @Override public PsiElement handleElementRename(@NotNull String newName){String old=getValue();int dot=old.lastIndexOf('.');return super.handleElementRename(dot<0?newName:old.substring(0,dot+1)+newName);}
    }
}
