package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import java.util.*;

/** Implementation search runs only on an explicit navigation action, not in editor analysis. */
public final class LegacyNavigation {
    private LegacyNavigation(){}
    public static List<XmlTag> collectTargets(PsiMethod method){
        LinkedHashSet<XmlTag> targets=new LinkedHashSet<>(MapperModel.statements(method));
        if(!targets.isEmpty())return List.copyOf(targets);
        Set<PsiMethod> implementations=new LinkedHashSet<>();if(method.getBody()!=null)implementations.add(method);
        OverridingMethodsSearch.search(method,GlobalSearchScope.projectScope(method.getProject()),true).forEach(implementation->{
            ProgressManager.checkCanceled();if(implementations.size()>=128)throw new IllegalArgumentException("DAO 实现超过 128 个，请从具体实现方法跳转");implementations.add(implementation);return true;
        });
        for(PsiMethod implementation:implementations)collectCalls(implementation,targets,new HashSet<>(),0);
        return List.copyOf(targets);
    }
    private static void collectCalls(PsiMethod method,Set<XmlTag> targets,Set<PsiMethod> seen,int depth){
        if(depth>8 || !seen.add(method) || method.getBody()==null)return;
        for(PsiMethodCallExpression call:PsiTreeUtil.findChildrenOfType(method.getBody(),PsiMethodCallExpression.class)){
            ProgressManager.checkCanceled();PsiExpression[] args=call.getArgumentList().getExpressions();
            if(args.length>0 && SqlApiReferences.isSqlCall(call)){
                String id=staticStatementId(args[0]);
                if(id!=null)targets.addAll(MapperModel.sqlTargets(method.getProject(),id));
            }else{
                PsiMethod helper=call.resolveMethod();if(helper!=null && helper.getContainingClass()!=null && method.getContainingClass()!=null && helper.getContainingClass().isEquivalentTo(method.getContainingClass()))collectCalls(helper,targets,seen,depth+1);
            }
        }
    }
    static String staticStatementId(PsiExpression expression){
        Object value=JavaPsiFacade.getInstance(expression.getProject()).getConstantEvaluationHelper().computeConstantExpression(expression);
        if(value instanceof String id)return id;
        // Pure syntax fallback for final string constants when the project SDK is incomplete.
        return stringConstant(expression,new HashSet<>(),0);
    }
    private static String stringConstant(PsiExpression expression,Set<PsiElement> stack,int depth){
        if(expression==null || depth>32 || !stack.add(expression))return null;
        try{
            if(expression instanceof PsiLiteralExpression literal && literal.getValue() instanceof String text)return text;
            if(expression instanceof PsiParenthesizedExpression p)return stringConstant(p.getExpression(),stack,depth+1);
            if(expression instanceof PsiPolyadicExpression p && p.getOperationTokenType()==JavaTokenType.PLUS){
                StringBuilder joined=new StringBuilder();for(PsiExpression operand:p.getOperands()){String text=stringConstant(operand,stack,depth+1);if(text==null)return null;joined.append(text);}return joined.toString();
            }
            if(expression instanceof PsiReferenceExpression reference && reference.resolve() instanceof PsiVariable variable && variable.hasModifierProperty(PsiModifier.FINAL)){
                String type=variable.getType().getCanonicalText();if(!type.equals("java.lang.String") && !type.equals("String"))return null;
                if(!stack.add(variable))return null;try{return stringConstant(variable.getInitializer(),stack,depth+1);}finally{stack.remove(variable);}
            }
            return null;
        }finally{stack.remove(expression);}
    }
    public static void navigate(PsiMethod method){
        var project=method.getProject();var pointer=SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);
        new Task.Backgroundable(project,"查找 DAO 对应的 iBATIS SQL",true){
            List<SmartPsiElementPointer<XmlTag>> found=List.of();Exception failure;
            @Override public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator){
                try{found=ReadAction.compute(()->{PsiMethod current=pointer.getElement();if(current==null)return List.of();return collectTargets(current).stream().map(t->SmartPointerManager.getInstance(project).createSmartPsiElementPointer(t)).toList();});}
                catch(ProcessCanceledException e){throw e;}catch(Exception e){failure=e;}
            }
            @Override public void onSuccess(){
                if(project.isDisposed())return;
                if(failure!=null){Messages.showErrorDialog(project,failure.getMessage(),"iBATIS SQL 跳转");return;}
                PsiElement[] targets=found.stream().map(SmartPsiElementPointer::getElement).filter(Objects::nonNull).toArray(PsiElement[]::new);
                if(targets.length==0){Messages.showInfoMessage(project,"未找到静态 SQL id。可在设置中配置接口与 namespace 映射，或从实际 SqlMapClient 调用的语句字符串跳转。","iBATIS SQL 跳转");return;}
                var editor=FileEditorManager.getInstance(project).getSelectedTextEditor();
                if(targets.length==1)com.intellij.util.PsiNavigateUtil.navigate(targets[0]);
                else {var popup=NavigationUtil.getPsiElementPopup(targets,"选择 iBATIS XML 语句");if(editor!=null)popup.showInBestPositionFor(editor);else popup.showCenteredInCurrentWindow(project);}
            }
        }.queue();
    }
}
