package de.peeeq.wurstscript;

import com.google.common.base.Preconditions;
import de.peeeq.wurstio.TimeTaker;
import de.peeeq.wurstscript.ast.CompilationUnit;
import de.peeeq.wurstscript.ast.WurstModel;
import de.peeeq.wurstscript.attributes.ErrorHandler;
import de.peeeq.wurstscript.attributes.names.DesugarArrayLength;
import de.peeeq.wurstscript.gui.WurstGui;
import de.peeeq.wurstscript.validation.GlobalCaches;
import de.peeeq.wurstscript.validation.WurstValidator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class WurstChecker {

    private final WurstGui gui;
    private final ErrorHandler errorHandler;
    private final boolean legacyJassTypeChecks;
    private final TimeTaker timeTaker;

    public WurstChecker(WurstGui gui, ErrorHandler errorHandler) {
        this(gui, errorHandler, false);
    }

    public WurstChecker(WurstGui gui, ErrorHandler errorHandler, boolean legacyJassTypeChecks) {
        this(gui, errorHandler, legacyJassTypeChecks, new TimeTaker.Default());
    }

    public WurstChecker(WurstGui gui, ErrorHandler errorHandler, boolean legacyJassTypeChecks,
                        TimeTaker timeTaker) {
        this.gui = gui;
        this.errorHandler = errorHandler;
        this.legacyJassTypeChecks = legacyJassTypeChecks;
        this.timeTaker = timeTaker != null ? timeTaker : new TimeTaker.Default();
    }

    public void checkProg(WurstModel root, Collection<CompilationUnit> toCheck) {
        Preconditions.checkNotNull(root);
        Preconditions.checkNotNull(toCheck);
        if (root.isEmpty()) {
            return;
        }
        timeTaker.measure("desugar array length", () -> new DesugarArrayLength().run(root));
        gui.sendProgress("Checking Files");

        if (errorHandler.getErrorCount() > 0) return;

        attachErrorHandler(root);
        clearGlobalCaches(root, toCheck);

        timeTaker.measure("expand modules", () -> expandModules(root));

        if (errorHandler.getErrorCount() > 0) return;

        SyntacticSugar syntacticSugar = new SyntacticSugar();
        List<SyntacticSugar.DeferredModuleCall> detachedTemplates = new ArrayList<>();
        timeTaker.measure("syntactic sugar", () -> {
            for (CompilationUnit cu : toCheck) {
                syntacticSugar.expandFieldIterations(cu);
                detachedTemplates.addAll(syntacticSugar.detachModuleTemplateFieldIterations(cu));
            }
        });
        try {
            // compute the flow attributes
            timeTaker.measure("flow attributes", () -> {
                for (CompilationUnit cu : toCheck) {
                    WurstValidator.computeFlowAttributes(cu);
                }
            });

            // validate the resource:
            timeTaker.measure("validate", () -> {
                WurstValidator validator = new WurstValidator(root, legacyJassTypeChecks, timeTaker);
                validator.validate(toCheck);
            });
        } finally {
            syntacticSugar.restoreModuleTemplateFieldIterations(detachedTemplates);
        }
    }

    private void clearGlobalCaches(WurstModel root, Collection<CompilationUnit> toCheck) {
        if (toCheck == root || toCheck.size() >= root.size()) {
            GlobalCaches.clearAll();
        } else {
            GlobalCaches.clearLookupCacheFor(toCheck);
        }
    }

    private void attachErrorHandler(WurstModel root) {
        for (CompilationUnit cu : root) {
            cu.getCuInfo().setCuErrorHandler(errorHandler);
        }
    }

    private void expandModules(WurstModel root) {
        for (CompilationUnit cu : root) {
            ModuleExpander.expandModules(cu);
        }
    }

}
