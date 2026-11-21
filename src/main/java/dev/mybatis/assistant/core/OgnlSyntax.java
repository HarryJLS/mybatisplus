package dev.mybatis.assistant.core;

import org.apache.ibatis.ognl.*;

/** Parse only: editor checks must never invoke user methods or evaluate expressions. */
public final class OgnlSyntax {
    private OgnlSyntax() {}
    public static Node parse(String expression) throws OgnlException {
        if (expression.length() > 16384) throw new OgnlException("表达式超过 16 KiB");
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '[' || c == '{') {
                if (++depth > 64) throw new OgnlException("表达式嵌套超过 64 层");
            } else if (c == ')' || c == ']' || c == '}') depth--;
        }
        try {
            Node result = (Node) Ognl.parseExpression(expression);
            check(result, 0, new int[]{0});
            return result;
        } catch (StackOverflowError e) { throw new OgnlException("表达式过于复杂"); }
    }
    private static void check(Node node, int depth, int[] count) throws OgnlException {
        if (depth > 64 || ++count[0] > 2048) throw new OgnlException("表达式超过语法树上限");
        for (int i = 0; i < node.jjtGetNumChildren(); i++) check(node.jjtGetChild(i), depth + 1, count);
    }
}
