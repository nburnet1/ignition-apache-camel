package com.noahburnette.ignition.apachecamel.camel;

import org.python.antlr.ast.Expression;
import org.python.antlr.ast.Lambda;
import org.python.antlr.ast.Name;
import org.python.core.CompileMode;
import org.python.core.CompilerFlags;
import org.python.core.ParserFacade;
import org.python.core.PyException;

/** Syntax inspection only: never evaluates code or resolves project globals. */
public final class InlineScript {
    private InlineScript() { }

    public static String source(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > 8192) {
            throw new IllegalArgumentException("Inline script requires an expression of 1–8192 characters");
        }
        final org.python.antlr.base.mod parsed;
        try {
            parsed = ParserFacade.parse(expression.strip(), CompileMode.eval, "<camel-inline>", new CompilerFlags());
        } catch (PyException invalid) {
            // Do not retain source text in diagnostics.
            throw new IllegalArgumentException("Inline script must be a valid Jython lambda expression");
        }
        if (!(parsed instanceof Expression value) || !(value.getInternalBody() instanceof Lambda lambda)) {
            throw new IllegalArgumentException("Inline script must be a lambda expression");
        }
        var args = lambda.getInternalArgs();
        if (args.getInternalArgs().size() != 2 || args.getInternalVararg() != null || args.getInternalKwarg() != null
            || !args.getInternalDefaults().isEmpty() || args.getInternalArgs().stream().anyMatch(arg -> !(arg instanceof Name))) {
            throw new IllegalArgumentException("Inline lambda must accept exactly two plain arguments, without defaults or varargs");
        }
        return "def invoke(payload, meta):\n    return (\n" + expression.strip() + "\n    )(payload, meta)\n";
    }
}
