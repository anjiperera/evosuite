package org.evosuite.ga.stoppingconditions;

import org.evosuite.Properties;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;

/**
 * Stop search after the archive exceeds the maximum number of statements
 *
 * @author Anjana Perera
 */

public class MaxArchiveStatementsStoppingCondition extends StoppingConditionImpl{

    private static final long serialVersionUID = -4524853279562896781L;

    /** Maximum number of statements */
    protected long maxStatements = Properties.MAX_ARCHIVE_STATEMENTS;

    protected long currentNumStatements = 0L;

    private boolean isMaxStatementsExceeded = false;

    /**
     * {@inheritDoc}
     *
     * We are finished when the maximum number of statements exceeds in the archive
     */
    @Override
    public boolean isFinished() {
        return isMaxStatementsExceeded;
    }

    public void setMaxStatementsExceeded(boolean isMaxStatementsExceeded){
        this.isMaxStatementsExceeded = isMaxStatementsExceeded;
    }

    /**
     * {@inheritDoc}
     *
     * Reset
     */
    @Override
    public void reset() {
        currentNumStatements = 0L;
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.StoppingCondition#setLimit(int)
     */
    /** {@inheritDoc} */
    @Override
    public void setLimit(long limit) {
        maxStatements = limit;
    }

    /** {@inheritDoc} */
    @Override
    public long getLimit() {
        return maxStatements;
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.StoppingCondition#getCurrentValue()
     */
    /** {@inheritDoc} */
    @Override
    public long getCurrentValue() {
        return currentNumStatements;
    }

    /** {@inheritDoc} */
    @Override
    public void forceCurrentValue(long value) {
        currentNumStatements = value;
    }

}
