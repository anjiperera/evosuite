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
 * Implementation of the DynaMOSA (Many Objective Sorting Algorithm) described in the paper
 * "Automated Test Case Generation as a Many-Objective Optimisation Problem with Dynamic Selection
 * of the Targets".
 * 
 * @author Annibale Panichella, Fitsum M. Kifetew, Paolo Tonella
 */
public class DynaMOSA<T extends Chromosome> extends AbstractMOSA<T> {

	private static final long serialVersionUID = 146182080947267628L;

	private static final Logger logger = LoggerFactory.getLogger(DynaMOSA.class);

	/** Manager to determine the test goals to consider at each generation */
	protected MultiCriteriatManager<T> goalsManager = null;

	protected CrowdingDistance<T> distance = new CrowdingDistance<T>();

	private long adjustGoalsOH = 0;

	private int currentIterationsWoImprovements = 0;
	private int currentUncoveredGoals = 0;
	private boolean triggerFired = false;

	/**
	 * Constructor based on the abstract class {@link AbstractMOSA}.
	 * 
	 * @param factory
	 */
	public DynaMOSA(ChromosomeFactory<T> factory) {
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

		long adjustGoalsStartTime = System.nanoTime();
		adjustCurrentGoals(false);
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

		if (!triggerFired) {
			if (goalsManager.getUncoveredGoals().size() == 0) {
				// trigger point to include non-buggy goals
				this.triggerFired = true;
				goalsManager.updateCurrentGoals();
				goalsManager.updateUncoveredGoals();
				goalsManager.updateMethods();
				goalsManager.updateBranchCoverageMaps();

				LoggingUtils.getEvoLogger().info(
						"Trigger to include non-buggy goals fired at {} seconds after {} generations",
						(int) (this.getCurrentTime() / 1000), this.currentIteration);
				LoggingUtils.getEvoLogger().info("Trigger cause: All buggy goals are covered");
			}
		}

		if (!triggerFired) {
			if (goalsManager.getUncoveredGoals().size() == this.currentUncoveredGoals) {
				this.currentIterationsWoImprovements++;
			} else {
				this.currentUncoveredGoals = goalsManager.getUncoveredGoals().size();
				this.currentIterationsWoImprovements = 0;
			}

			if (this.currentIterationsWoImprovements >= Properties.ITERATIONS_WO_IMPROVEMENT) {
				// trigger point to include non-buggy goals
				this.triggerFired = true;
				goalsManager.updateCurrentGoals();
				goalsManager.updateUncoveredGoals();
				goalsManager.updateMethods();
				goalsManager.updateBranchCoverageMaps();

				LoggingUtils.getEvoLogger().info(
						"Trigger to include non-buggy goals fired at {} seconds after {} generations",
						(int) (this.getCurrentTime() / 1000), this.currentIteration);
				LoggingUtils.getEvoLogger().info(
						"Trigger cause: Buggy goals coverage is not improved for {} generations",
						this.currentIterationsWoImprovements);
			}
		}

		logger.debug("Covered goals = {}", goalsManager.getCoveredGoals().size());
		logger.debug("Current goals = {}", goalsManager.getCurrentGoals().size());
		logger.debug("Uncovered goals = {}", goalsManager.getUncoveredGoals().size());
	}

	private void adjustCurrentGoals(boolean logInfo) {
		for (int actualBranchId : this.goalsManager.getBranchCoverageTrueMap().keySet()) {
			FitnessFunction ffTrue = this.goalsManager.getBranchCoverageTrueMap().get(actualBranchId);
			FitnessFunction ffFalse = this.goalsManager.getBranchCoverageFalseMap().get(actualBranchId);

			int numTestsTrueBranch = this.goalsManager.getNumTests(ffTrue.toString());
			int numTestsFalseBranch = this.goalsManager.getNumTests(ffFalse.toString());

			if (logInfo) {
				LoggingUtils.getEvoLogger().info("Branch: {}, Number of Tests: {}, Num of Paths: {}", ffTrue.toString(),
						numTestsTrueBranch, this.goalsManager.getNumPathsFor(ffTrue));
				LoggingUtils.getEvoLogger().info("Branch: {}, Number of Tests: {}, Num of Paths: {}", ffFalse.toString(),
						numTestsFalseBranch, this.goalsManager.getNumPathsFor(ffFalse));
			}

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
			} else {
				continue;
			}

			/*numTestsTrueBranch = numTestsTrueBranch == 0 ? 1 : numTestsTrueBranch;
			numTestsFalseBranch = numTestsFalseBranch == 0 ? 1 : numTestsFalseBranch;

			double scaleUpFactor = (double) numTestsTrueBranch / numTestsFalseBranch;
			if (Double.compare(scaleUpFactor, 1.0) > 0) {	// True Branch has more tests
				((BranchCoverageTestFitness) ffFalse).setNumTestCasesInZeroFront((int) Math.ceil(scaleUpFactor * 1));
				// 1 - Default Num Test Cases in Zero Front
				((BranchCoverageTestFitness) ffTrue).setNumTestCasesInZeroFront(0);
			} else {	// False Branch has more tests
				((BranchCoverageTestFitness) ffTrue).setNumTestCasesInZeroFront((int) Math.ceil(1 / scaleUpFactor));
				// 1 - Default Num Test Cases in Zero Front
				((BranchCoverageTestFitness) ffFalse).setNumTestCasesInZeroFront(0);
			}*/

		}
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

		// next generations
		while (!isFinished() /*&& this.goalsManager.getUncoveredGoals().size() > 0*/) {
			this.evolve();
			this.notifyIteration();
		}

		adjustCurrentGoals(true);
		LoggingUtils.getEvoLogger().info("Adjust Goals Overhead: {} ms",
				(double) (this.adjustGoalsOH) / 1000000);

		this.notifySearchFinished();
	}

	/** 
	 * {@inheritDoc}
	 */
	@Override
	protected Set<FitnessFunction<T>> getCoveredGoals() {
		return this.goalsManager.getCoveredGoals().keySet();
	}

	/** 
	 * {@inheritDoc}
	 */
	@Override
	protected int getNumberOfCoveredGoals() {
		return this.getCoveredGoals().size();
	}

	/** 
	 * {@inheritDoc}
	 */
	protected Set<FitnessFunction<T>> getUncoveredGoals() {
		return this.goalsManager.getUncoveredGoals();
	}

	/** 
	 * {@inheritDoc}
	 */
	@Override
	protected int getNumberOfUncoveredGoals() {
		return this.getUncoveredGoals().size();
	}

	/** 
	 * {@inheritDoc}
	 */
	@Override
	protected int getTotalNumberOfGoals() {
		return this.getNumberOfCoveredGoals() + this.getNumberOfUncoveredGoals();
	}

	/** 
	 * {@inheritDoc}
	 */
	@Override
	protected List<T> getSolutions() {
		if(goalsManager == null){
			return new ArrayList<T>();
		}

		List<T> suite = new ArrayList<T>(this.goalsManager.getArchive());
		return suite;
	}

	/** 
	 * {@inheritDoc}
	 */
	@Override
	protected TestSuiteChromosome generateSuite() {
		TestSuiteChromosome suite = new TestSuiteChromosome();
		for (T t : this.getSolutions()) {
			TestChromosome test = (TestChromosome) t;
			suite.addTest(test);
		}
		return suite;
	}

	/** 
	 * {@inheritDoc}
	 */
	@Override
	protected void calculateFitness(T c) {
		this.goalsManager.calculateFitness(c);
		this.notifyEvaluation(c);
	}

	/** 
	 * {@inheritDoc}
	 */
	@SuppressWarnings("unchecked")
	@Override
	public List<T> getBestIndividuals() {
		TestSuiteChromosome bestTestCases = this.generateSuite();

		if (bestTestCases.getTestChromosomes().isEmpty()) {
			// trivial case where there are no branches to cover or the archive is empty
			for (T test : this.population) {
				bestTestCases.addTest((TestChromosome) test);
			}
		}

		// compute overall fitness and coverage
		this.computeCoverageAndFitness(bestTestCases);

		List<T> bests = new ArrayList<T>(1);
		bests.add((T) bestTestCases);

		return bests;
	}

	/** 
	 * {@inheritDoc}
	 */
	@SuppressWarnings("unchecked")
	@Override
	public T getBestIndividual() {
		TestSuiteChromosome best = this.generateSuite();
		if (best.getTestChromosomes().isEmpty()) {
			for (T test : this.population) {
				best.addTest((TestChromosome) test);
			}
			for (TestSuiteFitnessFunction suiteFitness : this.suiteFitnessFunctions.keySet()) {
				best.setCoverage(suiteFitness, 0.0);
				best.setFitness(suiteFitness,  1.0);
			}
			return (T) best;
		}

		// compute overall fitness and coverage
		this.computeCoverageAndFitness(best);

		return (T) best;
	}

	/** 
	 * {@inheritDoc}
	 */
	@Override
	protected void computeCoverageAndFitness(TestSuiteChromosome suite) {
		for (Entry<TestSuiteFitnessFunction, Class<?>> entry : this.suiteFitnessFunctions.entrySet()) {
			TestSuiteFitnessFunction suiteFitnessFunction = entry.getKey();
			Class<?> testFitnessFunction = entry.getValue();

			int numberCoveredTargets = this.goalsManager.getNumberOfCoveredTargets(testFitnessFunction);
			int numberUncoveredTargets = this.goalsManager.getNumberOfUncoveredTargets(testFitnessFunction);
			int totalNumberTargets = numberCoveredTargets + numberUncoveredTargets;

			double coverage = totalNumberTargets == 0 ? 0.0
			    : ((double) numberCoveredTargets)
			    / ((double) (numberCoveredTargets + numberUncoveredTargets));

			suite.setFitness(suiteFitnessFunction, ((double) numberUncoveredTargets));
			suite.setCoverage(suiteFitnessFunction, coverage);
			suite.setNumOfCoveredGoals(suiteFitnessFunction, numberCoveredTargets);
			suite.setNumOfNotCoveredGoals(suiteFitnessFunction, numberUncoveredTargets);
		}
	}

}
