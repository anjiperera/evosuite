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
package org.evosuite.ga.metaheuristics.mosa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import org.evosuite.Properties;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.comparators.OnlyCrowdingComparator;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriatManager;
import org.evosuite.ga.metaheuristics.mosa.structural.StructuralGoalManager;
import org.evosuite.ga.operators.ranking.CrowdingDistance;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of PreMOSA (Predictive Many Objective Sorting Algorithm) described in the paper
 * "Guiding Search-Based Software Testing with Defect Prediction Information".
 *
 * @author Anjana Perera
 */
public class PreMOSA<T extends Chromosome> extends DynaMOSA<T> {

    private static final long serialVersionUID = 146182080947267628L;

    private static final Logger logger = LoggerFactory.getLogger(PreMOSA.class);

    /** Total time taken to adjust the goals (switch on/off goals) in nano seconds */
    private long adjustGoalsOH = 0;

    /** Number of current consecutive iterations without buggy goals coverage improvement */
    private int currentIterationsWoImprovements = 0;

    /** Current number of uncovered goals */
    private int currentUncoveredGoals = 0;

    /** If trigger to include non-buggy targets fired */
    private boolean triggerFired = false;

    /** If number of covered goals is zero */
    private boolean zeroGoalsCovered = true;

    /**
     * Constructor based on the abstract class {@link AbstractMOSA}.
     *
     * @param factory
     */
    public PreMOSA(ChromosomeFactory<T> factory) {
        super(factory);
    }

    /** {@inheritDoc} */
    @Override
    protected void evolve() {
        List<T> offspringPopulation = this.breedNextGeneration();

        // Create the union of parents and offSpring
        List<T> union = new ArrayList<T>(this.population.size() + offspringPopulation.size());
        union.addAll(this.population);
        union.addAll(offspringPopulation);

        // Ranking the union
        logger.debug("Union Size = {}", union.size());

        // Switch off targets to balance test coverage
        long adjustGoalsStartTime = System.nanoTime();
        adjustCurrentGoals();
        long adjustGoalEndTime = System.nanoTime();

        this.adjustGoalsOH += adjustGoalEndTime - adjustGoalsStartTime;

        // Ranking the union using the best rank algorithm (modified version of the non dominated sorting algorithm
        this.rankingFunction.computeRankingAssignment(union, this.goalsManager.getCurrentGoals());

        // let's form the next population using "preference sorting and non-dominated sorting" on the
        // updated set of goals
        int remain = Math.max(Properties.POPULATION, this.rankingFunction.getSubfront(0).size());
        int index = 0;
        List<T> front = null;
        this.population.clear();

        // Obtain the next front
        front = this.rankingFunction.getSubfront(index);

        if (front.isEmpty()) {	// zero front is empty
            index++;
            front = this.rankingFunction.getSubfront(index);
        }

        while ((remain > 0) && (remain >= front.size()) && !front.isEmpty()) {
            // Assign crowding distance to individuals
            this.distance.fastEpsilonDominanceAssignment(front, this.goalsManager.getCurrentGoals());

            // Add the individuals of this front
            this.population.addAll(front);

            // Decrement remain
            remain = remain - front.size();

            // Obtain the next front
            index++;
            if (remain > 0) {
                front = this.rankingFunction.getSubfront(index);
            }
        }

        // Remain is less than front(index).size, insert only the best one
        if (remain > 0 && !front.isEmpty()) { // front contains individuals to insert
            this.distance.fastEpsilonDominanceAssignment(front, this.goalsManager.getCurrentGoals());
            Collections.sort(front, new OnlyCrowdingComparator());
            for (int k = 0; k < remain; k++) {
                this.population.add(front.get(k));
            }

            remain = 0;
        }

        this.currentIteration++;

        addNonBuggyGoals();

        logger.debug("Covered goals = {}", goalsManager.getCoveredGoals().size());
        logger.debug("Current goals = {}", goalsManager.getCurrentGoals().size());
        logger.debug("Uncovered goals = {}", goalsManager.getUncoveredGoals().size());
    }

    /**
     * Switch on/off current goals to balance test coverage among goals based on number of tests per an independent
     * path of each goal
     */
    private void adjustCurrentGoals() {
        for (int actualBranchId : this.goalsManager.getBranchCoverageTrueMap().keySet()) {
            FitnessFunction ffTrue = this.goalsManager.getBranchCoverageTrueMap().get(actualBranchId);
            FitnessFunction ffFalse = this.goalsManager.getBranchCoverageFalseMap().get(actualBranchId);

            int numTestsTrueBranch = this.goalsManager.getNumTests(ffTrue.toString());
            int numTestsFalseBranch = this.goalsManager.getNumTests(ffFalse.toString());

            logger.debug("Branch: {}, Number of Tests: {}, Num of Paths: {}", ffTrue.toString(), numTestsTrueBranch,
                    this.goalsManager.getNumPathsFor(ffTrue));
            logger.debug("Branch: {}, Number of Tests: {}, Num of Paths: {}", ffFalse.toString(), numTestsFalseBranch,
                    this.goalsManager.getNumPathsFor(ffFalse));

            if (numTestsTrueBranch == 0 && numTestsFalseBranch == 0) {
                continue;
            }

            int numPathsTrueBranch = this.goalsManager.getNumPathsFor(ffTrue);
            int numPathsFalseBranch = this.goalsManager.getNumPathsFor(ffFalse);

            double testsPerPathTrueB = (double) numTestsTrueBranch / numPathsTrueBranch;
            double testsPerPathFalseB = (double) numTestsFalseBranch / numPathsFalseBranch;

            if (Double.compare(testsPerPathTrueB, testsPerPathFalseB) > 0) {
                this.goalsManager.getCurrentGoals().remove(ffTrue);
                this.goalsManager.getCurrentGoals().add(ffFalse);
            } else if (Double.compare(testsPerPathTrueB, testsPerPathFalseB) < 0) {
                this.goalsManager.getCurrentGoals().remove(ffFalse);
                this.goalsManager.getCurrentGoals().add(ffTrue);
            }
        }
    }

    /**
     * Add non-buggy goals to the search if number of consecutive iterations without buggy goals coverage improvement
     * is \geq org.evosuite.Properties.ITERATIONS_WO_IMPROVEMENT or if no buggy goal is covered for
     * org.evosuite.Properties.ZERO_COVERAGE_TRIGGER
     */
    private void addNonBuggyGoals() {
        if (!this.triggerFired) {
            if (goalsManager.getUncoveredGoals().size() == this.currentUncoveredGoals) {
                this.currentIterationsWoImprovements++;
            } else {
                this.currentUncoveredGoals = goalsManager.getUncoveredGoals().size();
                this.currentIterationsWoImprovements = 0;
            }

            if (this.currentIterationsWoImprovements >= Properties.ITERATIONS_WO_IMPROVEMENT) {
                // trigger point to include non-buggy goals
                this.triggerFired = true;
                updateGoalsManager();

                LoggingUtils.getEvoLogger().info(
                        "* Trigger to include non-buggy goals fired at {} seconds after {} generations",
                        (int) (this.getCurrentTime() / 1000), this.currentIteration);
                LoggingUtils.getEvoLogger().info(
                        "* Trigger cause: Buggy goals coverage is not improved for {} generations, current uncovered goals {}",
                        this.currentIterationsWoImprovements, this.currentUncoveredGoals);
            }
        }

        if (this.zeroGoalsCovered) {
            if (goalsManager.getCoveredGoals().size() > 0) {
                this.zeroGoalsCovered = false;
            }
        }

        if (zeroGoalsCovered && !triggerFired) {
            if (this.currentIteration >= Properties.ZERO_COVERAGE_TRIGGER) {
                this.triggerFired = true;
                updateGoalsManager();

                LoggingUtils.getEvoLogger().info(
                        "* Trigger to include non-buggy goals fired at {} seconds after {} generations",
                        (int) (this.getCurrentTime() / 1000), this.currentIteration);
                LoggingUtils.getEvoLogger().info(
                        "* Trigger cause: Buggy goals coverage is zero for {} generations, current uncovered goals {}",
                        this.currentIteration, this.currentUncoveredGoals);
            }
        }
    }

    /**
     * Update goals manager after the trigger is fired to include non-buggy goals in the search
     */
    private void updateGoalsManager() {
        goalsManager.updateCurrentGoals();
        goalsManager.updateUncoveredGoals();
        goalsManager.updateMethods();
        goalsManager.updateBranchCoverageMaps();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void generateSolution() {
        logger.debug("executing generateSolution function");

        this.goalsManager = new MultiCriteriatManager<T>(this.fitnessFunctions);

        if (this.goalsManager.getCurrentGoals().size() == 0) {
            // trigger point to include non-buggy goals
            this.triggerFired = true;
            goalsManager.updateCurrentGoals();
            goalsManager.updateUncoveredGoals();
            goalsManager.updateMethods();
            goalsManager.updateBranchCoverageMaps();

            LoggingUtils.getEvoLogger().info(
                    "Trigger to include non-buggy goals fired at {} seconds after {} generations",
                    (int) (this.getCurrentTime() / 1000), this.currentIteration);
            LoggingUtils.getEvoLogger().info("Trigger cause: No buggy goals");
        }

        LoggingUtils.getEvoLogger().info("* Initial Number of Goals in DynMOSA = " +
                this.goalsManager.getCurrentGoals().size() +" / "+ this.getUncoveredGoals().size());

        logger.debug("Initial Number of Goals = " + this.goalsManager.getCurrentGoals().size());

        //initialize population
        if (this.population.isEmpty()) {
            this.initializePopulation();
        }

        // update current goals
        this.calculateFitness();

        // Calculate dominance ranks and crowding distance
        this.rankingFunction.computeRankingAssignment(this.population, this.goalsManager.getCurrentGoals());

        for (int i = 0; i < this.rankingFunction.getNumberOfSubfronts(); i++){
            this.distance.fastEpsilonDominanceAssignment(this.rankingFunction.getSubfront(i), this.goalsManager.getCurrentGoals());
        }

        this.currentUncoveredGoals = goalsManager.getUncoveredGoals().size();

        if (zeroGoalsCovered) {
            if (goalsManager.getCoveredGoals().size() > 0) {
                zeroGoalsCovered = false;
            }
        }

        // next generations
        while (!isFinished() /*&& this.goalsManager.getUncoveredGoals().size() > 0*/) {
            this.evolve();
            this.notifyIteration();
        }

        LoggingUtils.getEvoLogger().info("Adjust Goals Overhead: {} ms",
                (double) (this.adjustGoalsOH) / 1000000);

        this.notifySearchFinished();
    }

}
