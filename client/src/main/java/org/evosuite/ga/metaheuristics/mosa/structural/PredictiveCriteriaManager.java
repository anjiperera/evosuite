/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.metaheuristics.mosa.structural;

import java.util.*;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.Properties.Criterion;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.exception.ExceptionCoverageFactory;
import org.evosuite.coverage.exception.ExceptionCoverageHelper;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.exception.TryCatchCoverageTestFitness;
import org.evosuite.coverage.io.input.InputCoverageTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageTestFitness;
import org.evosuite.coverage.mutation.StrongMutationTestFitness;
import org.evosuite.coverage.mutation.WeakMutationTestFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.setup.CallContext;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.callgraph.CallGraph;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.evosuite.Properties.Criterion.*;

public class PredictiveCriteriaManager<T extends Chromosome> extends MultiCriteriatManager<T>{

    private static final Logger logger = LoggerFactory.getLogger(PredictiveCriteriaManager.class);

    /** Current methods in the search */
    private Set<MethodCoverageTestFitness> methods = new HashSet<>();

    private Set<MethodCoverageTestFitness> nonBuggyMethods = new HashSet<>();;

    private Set<FitnessFunction<T>> nonBuggyGoals;

    /** Branch(less) coverage maps of non-buggy goals */
    private final Map<Integer, FitnessFunction<T>> nBBranchCoverageTrueMap = new LinkedHashMap<Integer, FitnessFunction<T>>();
    private final Map<Integer, FitnessFunction<T>> nBBranchCoverageFalseMap = new LinkedHashMap<Integer, FitnessFunction<T>>();
    private final Map<String, FitnessFunction<T>> nBBranchlessMethodCoverageMap = new LinkedHashMap<String, FitnessFunction<T>>();

    public PredictiveCriteriaManager(List<FitnessFunction<T>> fitnessFunctions) {
        super(fitnessFunctions);
    }

    @Override
    protected void init(List<FitnessFunction<T>> fitnessFunctions) {
        nonBuggyGoals = new HashSet<FitnessFunction<T>>(fitnessFunctions.size());

        // initialize uncovered goals and find nonBuggyGoals
        for (FitnessFunction<T> ff : fitnessFunctions) {
            if (ff instanceof BranchCoverageTestFitness) {
                if (((BranchCoverageTestFitness) ff).isBuggy()) {
                    uncoveredGoals.add(ff);
                } else {
                    nonBuggyGoals.add(ff);
                }
            } else if (ff instanceof MethodCoverageTestFitness) {
                if (((MethodCoverageTestFitness) ff).isBuggy()) {
                    uncoveredGoals.add(ff);
                } else {
                    nonBuggyGoals.add(ff);
                }
            } else {
                uncoveredGoals.add(ff);
            }
        }

        LoggingUtils.getEvoLogger().info("* Total Number of Buggy Goals: " + uncoveredGoals.size());
        LoggingUtils.getEvoLogger().info("* Total Number of Non-Buggy Goals: " + nonBuggyGoals.size());

        // initialize the dependency graph among branches
        this.graph = getControlDepencies4Branches();

        // initialize methods and non-buggy methods
        initMethods(fitnessFunctions);

        // initialize the dependency graph between branches and other coverage targets (e.g., statements)
        // let's derive the dependency graph between branches and other coverage targets (e.g., statements)
        for (Criterion criterion : Properties.CRITERION){
            switch (criterion){
                case BRANCH:
                    break; // branches have been handled by getControlDepencies4Branches
                case EXCEPTION:
                    break; // exception coverage is handled by calculateFitness
                case LINE:
                    addDependencies4Line();
                    break;
                case STATEMENT:
                    addDependencies4Statement();
                    break;
                case WEAKMUTATION:
                    addDependencies4WeakMutation();
                    break;
                case STRONGMUTATION:
                    addDependencies4StrongMutation();
                    break;
                case METHOD:
                    addDependencies4Methods();
                    break;
                case INPUT:
                    addDependencies4Input();
                    break;
                case OUTPUT:
                    addDependencies4Output();
                    break;
                case TRYCATCH:
                    addDependencies4TryCatch();
                    break;
                case METHODNOEXCEPTION:
                    addDependencies4MethodsNoException();
                    break;
                case CBRANCH:
                    addDependencies4CBranch();
                    break;
                default:
                    LoggingUtils.getEvoLogger().error("The criterion {} is not currently supported in DynaMOSA", criterion.name());
            }
        }

        // initialize current goals
        for (FitnessFunction<T> ff : graph.getRootBranches()) {
            if (((BranchCoverageTestFitness) ff).isBuggy()) {
                this.currentGoals.add(ff);
            }
        }

        // Calculate number of independent paths leading up from each target (goal)
        calculateIndependentPaths(fitnessFunctions);
    }

    private void initMethods(List<FitnessFunction<T>> fitnessFunctions) {
        for (FitnessFunction<T> ff : fitnessFunctions) {
            if (ff instanceof MethodCoverageTestFitness) {
                if (((MethodCoverageTestFitness) ff).isBuggy()) {
                    this.methods.add((MethodCoverageTestFitness) ff);
                } else {
                    this.nonBuggyMethods.add((MethodCoverageTestFitness) ff);
                }
            }
        }
    }

    @Override
    protected void initializeMaps(Set<FitnessFunction<T>> set){
        for (FitnessFunction<T> ff : set) {
            BranchCoverageTestFitness goal = (BranchCoverageTestFitness) ff;
            // Skip instrumented branches - we only want real branches
            if(goal.getBranch() != null) {
                if(goal.getBranch().isInstrumented()) {
                    continue;
                }
            }

            if (goal.getBranch() == null) {
                if (goal.isBuggy()) {
                    branchlessMethodCoverageMap.put(goal.getClassName() + "." + goal.getMethod(), ff);
                } else {
                    nBBranchlessMethodCoverageMap.put(goal.getClassName() + "." + goal.getMethod(), ff);
                }
            } else {
                if (goal.isBuggy()) {
                    if (goal.getBranchExpressionValue()) {
                        branchCoverageTrueMap.put(goal.getBranch().getActualBranchId(), ff);
                    }
                    else {
                        branchCoverageFalseMap.put(goal.getBranch().getActualBranchId(), ff);
                    }
                } else {
                    if (goal.getBranchExpressionValue()) {
                        nBBranchCoverageTrueMap.put(goal.getBranch().getActualBranchId(), ff);
                    }
                    else {
                        nBBranchCoverageFalseMap.put(goal.getBranch().getActualBranchId(), ff);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void calculateFitness(T c) {
        // run the test
        TestCase test = ((TestChromosome) c).getTestCase();
        ExecutionResult result = TestCaseExecutor.runTest(test);
        ((TestChromosome) c).setLastExecutionResult(result);
        c.setChanged(false);

        if (result.hasTimeout() || result.hasTestException()){
            for (FitnessFunction<T> f : currentGoals)
                c.setFitness(f, Double.MAX_VALUE);
            return;
        }

        Set<MethodCoverageTestFitness> visitedMethods = new HashSet<>();

        // 1) we update the set of currents goals
        Set<FitnessFunction<T>> visitedTargets = new LinkedHashSet<FitnessFunction<T>>(uncoveredGoals.size()*2);
        LinkedList<FitnessFunction<T>> targets = new LinkedList<FitnessFunction<T>>();
        targets.addAll(this.currentGoals);

        while (targets.size()>0){
            FitnessFunction<T> fitnessFunction = targets.poll();

            int past_size = visitedTargets.size();
            visitedTargets.add(fitnessFunction);
            if (past_size == visitedTargets.size())
                continue;

            double value = fitnessFunction.getFitness(c);
            if (value == 0.0) {
                if (fitnessFunction instanceof MethodCoverageTestFitness) {
                    visitedMethods.add((MethodCoverageTestFitness) fitnessFunction);
                }

                updateCoveredGoals(fitnessFunction, c);
                if (fitnessFunction instanceof BranchCoverageTestFitness){
                    for (FitnessFunction<T> child : graph.getStructuralChildren(fitnessFunction)){
                        targets.addLast(child);
                    }
                    for (FitnessFunction<T> dependentTarget : dependencies.get(fitnessFunction)){
                        targets.addLast(dependentTarget);
                    }
                }
            } else {
                currentGoals.add(fitnessFunction);
            }
        }
        //currentGoals.removeAll(coveredGoals.keySet());	//not removing covered goals from currentGoals
        // 2) we update the archive
        for (Integer branchid : result.getTrace().getCoveredFalseBranches()){
            FitnessFunction<T> branch = this.branchCoverageFalseMap.get(branchid);
            if (branch == null)
                continue;
            updateCoveredGoals((FitnessFunction<T>) branch, c);
        }
        for (Integer branchid : result.getTrace().getCoveredTrueBranches()){
            FitnessFunction<T> branch = this.branchCoverageTrueMap.get(branchid);
            if (branch == null)
                continue;
            updateCoveredGoals((FitnessFunction<T>) branch, c);
        }
        for (String method : result.getTrace().getCoveredBranchlessMethods()){
            FitnessFunction<T> branch = this.branchlessMethodCoverageMap.get(method);
            if (branch == null)
                continue;
            updateCoveredGoals((FitnessFunction<T>) branch, c);
        }

        // let's manage the exception coverage
        if (ArrayUtil.contains(Properties.CRITERION, EXCEPTION)){
            // if one of the coverage criterion is Criterion.EXCEPTION,
            // then we have to analyze the results of the execution do look
            // for generated exceptions
            Set<ExceptionCoverageTestFitness> set = deriveCoveredExceptions(c);
            for (ExceptionCoverageTestFitness exp : set){
                // let's update the list of fitness functions
                updateCoveredGoals((FitnessFunction<T>) exp, c);
                // new covered exceptions (goals) have to be added to the archive
                if (!ExceptionCoverageFactory.getGoals().containsKey(exp.getKey())){
                    // let's update the newly discovered exceptions to ExceptionCoverageFactory
                    ExceptionCoverageFactory.getGoals().put(exp.getKey(), exp);
                }
            }
        }

        if (ArrayUtil.contains(Properties.CRITERION, METHOD)){
            for (MethodCoverageTestFitness methodFf : this.methods) {
                if (!visitedMethods.contains(methodFf)) {
                    double value = ((FitnessFunction) methodFf).getFitness(c);
                    if (value == 0.0) {
                        updateCoveredGoals((FitnessFunction) methodFf, c);
                    }
                }
            }
        }

    }

    public Map<Integer, FitnessFunction<T>> getBranchCoverageTrueMap() {
        return branchCoverageTrueMap;
    }

    public Map<Integer, FitnessFunction<T>> getBranchCoverageFalseMap() {
        return branchCoverageFalseMap;
    }

    public void updateCurrentGoals() {
        for (FitnessFunction<T> ff : graph.getRootBranches()) {
            if (!((BranchCoverageTestFitness) ff).isBuggy()) {
                this.currentGoals.add(ff);
            }
        }
    }

    public void updateUncoveredGoals() {
        this.uncoveredGoals.addAll(this.nonBuggyGoals);
    }

    public void updateMethods() {
        this.methods.addAll(this.nonBuggyMethods);
    }

    public void updateBranchCoverageMaps() {
        branchCoverageTrueMap.putAll(nBBranchCoverageTrueMap);
        branchCoverageFalseMap.putAll(nBBranchCoverageFalseMap);
        branchlessMethodCoverageMap.putAll(nBBranchlessMethodCoverageMap);
    }
}
