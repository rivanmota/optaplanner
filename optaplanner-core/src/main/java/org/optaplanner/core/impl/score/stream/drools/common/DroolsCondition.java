/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.core.impl.score.stream.drools.common;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.drools.core.base.accumulators.CollectSetAccumulateFunction;
import org.drools.model.DSL;
import org.drools.model.Drools;
import org.drools.model.Index;
import org.drools.model.PatternDSL;
import org.drools.model.PatternDSL.PatternDef;
import org.drools.model.Variable;
import org.drools.model.view.ExprViewItem;
import org.drools.model.view.ViewItem;
import org.drools.model.view.ViewItemBuilder;
import org.kie.api.runtime.rule.RuleContext;
import org.optaplanner.core.api.function.TriFunction;
import org.optaplanner.core.api.score.Score;
import org.optaplanner.core.api.score.holder.AbstractScoreHolder;
import org.optaplanner.core.impl.score.stream.drools.bi.DroolsBiCondition;
import org.optaplanner.core.impl.score.stream.drools.bi.DroolsBiRuleStructure;
import org.optaplanner.core.impl.score.stream.drools.quad.DroolsQuadCondition;
import org.optaplanner.core.impl.score.stream.drools.quad.DroolsQuadRuleStructure;
import org.optaplanner.core.impl.score.stream.drools.tri.DroolsTriCondition;
import org.optaplanner.core.impl.score.stream.drools.tri.DroolsTriRuleStructure;
import org.optaplanner.core.impl.score.stream.drools.uni.DroolsUniCondition;
import org.optaplanner.core.impl.score.stream.drools.uni.DroolsUniRuleStructure;

import static org.drools.model.DSL.accFunction;
import static org.drools.model.PatternDSL.alphaIndexedBy;
import static org.drools.model.PatternDSL.pattern;

/**
 * Encapsulates the low-level rule creation and manipulation operations via the Drools executable model DSL
 * (see {@link PatternDSL}.
 *
 * @param <T> type of Drools rule that we operate on
 */
public abstract class DroolsCondition<T extends DroolsRuleStructure> {

    protected final T ruleStructure;

    protected DroolsCondition(T ruleStructure) {
        this.ruleStructure = ruleStructure;
    }

    protected <NewA, InTuple, OutTuple, __> DroolsUniCondition<NewA> collect(
            DroolsAbstractAccumulateFunctionBridge<__, InTuple, OutTuple> accumulateFunctionBridge,
            BiFunction<PatternDef<Object>, Variable<InTuple>, PatternDef<Object>> bindFunction) {
        Variable<InTuple> tupleVariable = ruleStructure.createVariable("tuple");
        PatternDef<Object> mainAccumulatePattern = ruleStructure.getPrimaryPatternBuilder()
                .expand(p -> bindFunction.apply(p, tupleVariable))
                .build();
        ViewItem<?> innerAccumulatePattern = getInnerAccumulatePattern(mainAccumulatePattern);
        Variable<NewA> outputVariable = ruleStructure.createVariable("collected");
        ViewItem<?> outerAccumulatePattern = DSL.accumulate(innerAccumulatePattern,
                accFunction(() -> accumulateFunctionBridge, tupleVariable).as(outputVariable));
        DroolsUniRuleStructure<NewA> newRuleStructure = ruleStructure.recollect(outputVariable, outerAccumulatePattern);
        return new DroolsUniCondition<>(newRuleStructure);
    }

    protected <NewA> DroolsUniCondition<NewA> group(
            BiFunction<PatternDef<Object>, Variable<NewA>, PatternDef<Object>> bindFunction) {
        return universalGroup(bindFunction, (var, pattern, accumulate) -> {
            DroolsUniRuleStructure<NewA> newRuleStructure = ruleStructure.regroup(var, pattern, accumulate);
            return new DroolsUniCondition<>(newRuleStructure);
        });
    }

    public <NewA, NewB> DroolsBiCondition<NewA, NewB> groupBi(
            BiFunction<PatternDef<Object>, Variable<BiTuple<NewA, NewB>>, PatternDef<Object>> bindFunction) {
        return universalGroup(bindFunction, (var, pattern, accumulate) -> {
            DroolsBiRuleStructure<NewA, NewB> newRuleStructure = ruleStructure.regroupBi(var, pattern, accumulate);
            return new DroolsBiCondition<>(newRuleStructure);
        });
    }

    private <InTuple, R extends DroolsRuleStructure, C extends DroolsCondition<R>> C universalGroup(
            BiFunction<PatternDef<Object>, Variable<InTuple>, PatternDef<Object>> bindFunction,
            Mutator<InTuple, R, C> mutator) {
        Variable<InTuple> mappedVariable = ruleStructure.createVariable("biMapped");
        PatternDSL.PatternDef<Object> mainAccumulatePattern = ruleStructure.getPrimaryPatternBuilder()
                .expand(p -> bindFunction.apply(p, mappedVariable))
                .build();
        ViewItem<?> innerAccumulatePattern = getInnerAccumulatePattern(mainAccumulatePattern);
        Variable<Set<InTuple>> tupleSet =
                (Variable<Set<InTuple>>) ruleStructure.createVariable(Set.class,"tupleSet");
        PatternDSL.PatternDef<Set<InTuple>> pattern = pattern(tupleSet)
                .expr("Non-empty", set -> !set.isEmpty(),
                        alphaIndexedBy(Integer.class, Index.ConstraintType.GREATER_THAN, -1, Set::size, 0));
        ExprViewItem<Object> accumulate = DSL.accumulate(innerAccumulatePattern,
                accFunction(CollectSetAccumulateFunction.class, mappedVariable).as(tupleSet));
        return mutator.apply(tupleSet, pattern, accumulate);
    }

    protected <NewA, NewB, InTuple> DroolsBiCondition<NewA, NewB> groupWithCollect(
            Supplier<? extends DroolsAbstractGroupByInvoker<InTuple>> invokerSupplier) {
        return universalGroupWithCollect(invokerSupplier, (var, pattern, accumulate) -> {
            DroolsBiRuleStructure<NewA, NewB> newRuleStructure =
                    ruleStructure.regroupBi((Variable) var, (PatternDef) pattern, accumulate);
            return new DroolsBiCondition<>(newRuleStructure);
        });
    }

    protected <NewA, NewB, NewC, InTuple> DroolsTriCondition<NewA, NewB, NewC> groupBiWithCollect(
            Supplier<? extends DroolsAbstractGroupByInvoker<InTuple>> invokerSupplier) {
        return universalGroupWithCollect(invokerSupplier, (var, pattern, accumulate) -> {
            DroolsTriRuleStructure<NewA, NewB, NewC> newRuleStructure =
                    ruleStructure.regroupBiToTri((Variable) var, (PatternDef) pattern, accumulate);
            return new DroolsTriCondition<>(newRuleStructure);
        });
    }

    protected <NewA, NewB, NewC, NewD, InTuple> DroolsQuadCondition<NewA, NewB, NewC, NewD> groupBiWithCollectBi(
            Supplier<? extends DroolsAbstractGroupByInvoker<InTuple>> invokerSupplier) {
        return universalGroupWithCollect(invokerSupplier, (var, pattern, accumulate) -> {
            DroolsQuadRuleStructure<NewA, NewB, NewC, NewD> newRuleStructure =
                    ruleStructure.regroupBiToQuad((Variable) var, (PatternDef) pattern, accumulate);
            return new DroolsQuadCondition<>(newRuleStructure);
        });
    }

    private <InTuple, R extends DroolsRuleStructure, C extends DroolsCondition<R>> C universalGroupWithCollect(
            Supplier<? extends DroolsAbstractGroupByInvoker<InTuple>> invokerSupplier,
            Mutator<InTuple, R, C> mutator) {
        Variable<Set<InTuple>> tupleSet =
                (Variable<Set<InTuple>>) ruleStructure.createVariable(Set.class, "tupleSet");
        PatternDSL.PatternDef<Set<InTuple>> pattern = pattern(tupleSet)
                .expr("Non-empty", set -> !set.isEmpty(),
                        alphaIndexedBy(Integer.class, Index.ConstraintType.GREATER_THAN, -1, Set::size, 0));
        PatternDSL.PatternDef<Object> innerCollectingPattern = ruleStructure.getPrimaryPatternBuilder().build();
        ViewItem<?> innerAccumulatePattern = getInnerAccumulatePattern(innerCollectingPattern);
        ViewItem<?> accumulate = DSL.accumulate(innerAccumulatePattern, accFunction(invokerSupplier).as(tupleSet));
        return mutator.apply(tupleSet, pattern, accumulate);
    }

    protected <S extends Score<S>, H extends AbstractScoreHolder<S>> void impactScore(Drools drools, H scoreHolder) {
        RuleContext kcontext = (RuleContext) drools;
        scoreHolder.impactScore(kcontext);
    }

    protected <S extends Score<S>, H extends AbstractScoreHolder<S>> void impactScore(Drools drools, H scoreHolder,
            int impact) {
        RuleContext kcontext = (RuleContext) drools;
        scoreHolder.impactScore(kcontext, impact);
    }

    protected <S extends Score<S>, H extends AbstractScoreHolder<S>> void impactScore(Drools drools, H scoreHolder,
            long impact) {
        RuleContext kcontext = (RuleContext) drools;
        scoreHolder.impactScore(kcontext, impact);
    }

    protected <S extends Score<S>, H extends AbstractScoreHolder<S>> void impactScore(Drools drools, H scoreHolder,
            BigDecimal impact) {
        RuleContext kcontext = (RuleContext) drools;
        scoreHolder.impactScore(kcontext, impact);
    }

    protected ViewItem<?> getInnerAccumulatePattern(PatternDef<Object> mainAccumulatePattern) {
        Stream<ViewItemBuilder<?>> primaryAndPrerequisites = Stream.concat(ruleStructure.getPrerequisites().stream(),
                Stream.of(mainAccumulatePattern));
        Stream<ViewItemBuilder<?>> all = Stream.concat(primaryAndPrerequisites, ruleStructure.getDependents().stream());
        ViewItem[] items = all.toArray(ViewItem[]::new);
        return PatternDSL.and(items[0], Arrays.copyOfRange(items, 1, items.length));
    }

    public T getRuleStructure() {
        return ruleStructure;
    }

    @FunctionalInterface
    private interface Mutator<InTuple, R extends DroolsRuleStructure, C extends DroolsCondition<R>> extends
            TriFunction<Variable<Set<InTuple>>, PatternDef<Set<InTuple>>, ViewItem<?>, C> {

    }
}
