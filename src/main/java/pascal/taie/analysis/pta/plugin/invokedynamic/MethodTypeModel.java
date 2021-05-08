/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis.pta.plugin.invokedynamic;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.PointerAnalysis;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.ir.exp.ClassLiteral;
import pascal.taie.ir.exp.MethodType;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.StringReps;
import pascal.taie.language.classes.Subsignature;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeManager;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static pascal.taie.util.collection.CollectionUtils.addToMapSet;
import static pascal.taie.util.collection.CollectionUtils.newHybridMap;

/**
 * Model invocations to MethodType.methodType(*);
 */
class MethodTypeModel {

    private final PointerAnalysis pta;

    private final CSManager csManager;

    private final HeapModel heapModel;

    /**
     * Default heap context for MethodType objects.
     */
    private final Context defaultHctx;

    private final JMethod methodType0Arg;

    private final JMethod methodType1Arg;

    private final JMethod methodTypeMT;

    private final List<JMethod> methodTypeMethods;

    private final Map<Var, Set<Invoke>> methodTypeVars = newHybridMap();

    public MethodTypeModel(PointerAnalysis pta) {
        this.pta = pta;
        csManager = pta.getCSManager();
        heapModel = pta.getHeapModel();
        defaultHctx = pta.getContextSelector().getDefaultContext();
        ClassHierarchy hierarchy = pta.getHierarchy();
        TypeManager typeManager = pta.getTypeManager();
        JClass methodType = hierarchy.getClass(StringReps.METHOD_TYPE);
        Type mt = methodType.getType();
        Type klass = typeManager.getClassType(StringReps.CLASS);
        methodType0Arg = methodType.getDeclaredMethod(
                Subsignature.get("methodType", List.of(klass), mt));
        methodType1Arg = methodType.getDeclaredMethod(
                Subsignature.get("methodType", List.of(klass, klass), mt));
        methodTypeMT = methodType.getDeclaredMethod(
                Subsignature.get("methodType", List.of(klass, mt), mt));
        methodTypeMethods = List.of(methodType0Arg, methodType1Arg, methodTypeMT);
    }

    void handleNewInvoke(Invoke invoke) {
        if (invoke.isStatic()) {
            JMethod target = invoke.getMethodRef().resolve();
            if (methodTypeMethods.contains(target)) {
                // record MethodType-related variables
                invoke.getInvokeExp().getArgs().forEach(arg ->
                        addToMapSet(methodTypeVars, arg, invoke));
            }
        }
    }

    boolean isRelevantVar(Var var) {
        return methodTypeVars.containsKey(var);
    }

    void handleNewPointsToSet(CSVar csVar, PointsToSet pts) {
        methodTypeVars.get(csVar.getVar()).forEach(invoke -> {
            JMethod target = invoke.getMethodRef().resolve();
            if (target.equals(methodType0Arg)) {
                processMethodType0Arg(csVar, pts, invoke);
            } else if (target.equals(methodType1Arg)) {
                processMethodType1Arg(csVar, pts, invoke);
            } else if (target.equals(methodTypeMT)) {
                processMethodTypeMT(csVar, pts, invoke);
            }
        });
    }

    private void processMethodType0Arg(CSVar csVar, PointsToSet pts, Invoke invoke) {
        Var result = invoke.getResult();
        if (result != null) {
            PointsToSet mtObjs = PointsToSetFactory.make();
            pts.forEach(cls -> {
                Type retType = toType(cls);
                if (retType != null) {
                    MethodType mt = MethodType.get(Collections.emptyList(), retType);
                    Obj mtObj = heapModel.getConstantObj(mt);
                    mtObjs.addObject(csManager.getCSObj(defaultHctx, mtObj));
                }
            });
            if (!mtObjs.isEmpty()) {
                pta.addVarPointsTo(csVar.getContext(), result, mtObjs);
            }
        }
    }

    private void processMethodType1Arg(CSVar csVar, PointsToSet pts, Invoke invoke) {
        Var result = invoke.getResult();
        if (result != null) {
            List<PointsToSet> args = getArgs(csVar, pts, invoke);
            PointsToSet retObjs = args.get(0);
            PointsToSet paramObjs = args.get(1);
            PointsToSet mtObjs = PointsToSetFactory.make();
            retObjs.forEach(retObj ->
                    paramObjs.forEach(paramObj -> {
                        Type retType = toType(retObj);
                        Type paramType = toType(paramObj);
                        if (retType != null && paramType != null) {
                            MethodType mt = MethodType.get(List.of(paramType), retType);
                            Obj mtObj = heapModel.getConstantObj(mt);
                            mtObjs.addObject(csManager.getCSObj(defaultHctx, mtObj));
                        }
                    })
            );
            if (!mtObjs.isEmpty()) {
                pta.addVarPointsTo(csVar.getContext(), result, mtObjs);
            }
        }
    }

    private void processMethodTypeMT(CSVar csVar, PointsToSet pts, Invoke invoke) {
        Var result = invoke.getResult();
        if (result != null) {
            List<PointsToSet> args = getArgs(csVar, pts, invoke);
            PointsToSet retObjs = args.get(0);
            PointsToSet mtObjs = args.get(1);
            PointsToSet resultMTObjs = PointsToSetFactory.make();
            retObjs.forEach(retObj ->
                    mtObjs.forEach(mtObj -> {
                        Type retType = toType(retObj);
                        MethodType mt = toMethodType(mtObj);
                        if (retType != null && mt != null) {
                            MethodType resultMT = MethodType.get(mt.getParamTypes(), retType);
                            Obj resultMTObj = heapModel.getConstantObj(resultMT);
                            resultMTObjs.addObject(csManager.getCSObj(defaultHctx, resultMTObj));
                        }
                    })
            );
            if (!resultMTObjs.isEmpty()) {
                pta.addVarPointsTo(csVar.getContext(), result, resultMTObjs);
            }
        }
    }

    /**
     * When the points-to set of a variable (say v) changes, this convenient
     * method returns the points-to sets of all relevant variables,
     * i.e., v and the variables all are arguments of the given call site.
     * For v, this method returns the changed part, for other variables,
     * it just returns their current points-to sets.
     * @param csVar the variable whose points-to set changes
     * @param pts the points-to set of csVar containing new discovered objects
     * @param invoke the call site which contain csVar
     * @return points-to sets of all arguments of invoke.
     */
    private List<PointsToSet> getArgs(CSVar csVar, PointsToSet pts, Invoke invoke) {
        return invoke.getInvokeExp().getArgs()
                .stream()
                .map(arg -> {
                    if (arg.equals(csVar.getVar())) {
                        return pts;
                    } else {
                        CSVar csArg = csManager.getCSVar(csVar.getContext(), arg);
                        return pta.getPointsToSetOf(csArg);
                    }
                })
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Convert a CSObj of class to corresponding type. If the object is
     * not a class constant, then return null.
     */
    private static @Nullable Type toType(CSObj csObj) {
        Object alloc = csObj.getObject().getAllocation();
        if (alloc instanceof ClassLiteral) {
            ClassLiteral klass = (ClassLiteral) alloc;
            return klass.getTypeValue();
        } else {
            return null;
        }
    }

    /**
     * Convert a CSObj of MethodType to corresponding MethodType.
     * If the object is not a MethodType, then return null.
     */
    private static @Nullable MethodType toMethodType(CSObj csObj) {
        Object alloc = csObj.getObject().getAllocation();
        if (alloc instanceof MethodType) {
            return (MethodType) alloc;
        } else {
            return null;
        }
    }
}
