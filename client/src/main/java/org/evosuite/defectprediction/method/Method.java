package org.evosuite.defectprediction.method;

import org.evosuite.utils.LoggingUtils;

import java.util.ArrayList;
import java.util.List;

public class Method {

    private String fqMethodName;
    private double defectScore;

    private int numBranches;
    private double normDefectScore;
    private double weight;

    private String evoFormatName;

    private List<Integer> branchIds = new ArrayList<>();

    public Method(String fqMethodName, double defectScore) {
        this.fqMethodName = fqMethodName;
        this.defectScore = defectScore;
    }

    public int getNumBranches() {
        return numBranches;
    }

    public void setNumBranches(int numBranches) {
        this.numBranches = numBranches;
    }

    public double getDefectScore() {
        return defectScore;
    }

    public void normalizeDefectScore(double sumDefectScores) {
        normDefectScore = defectScore / sumDefectScores;
        this.weight = normDefectScore;
    }

    public double getNormDefectScore() {
        return normDefectScore;
    }

    public String getEvoFormatName() {
        return evoFormatName;
    }

    public void setEvoFormatName(String evoFormatName) {
        this.evoFormatName = evoFormatName;
    }

    public double getWeight() {
        return weight;
    }

    public List<Integer> getBranchIds() {
        return branchIds;
    }

    public void setBranchIds(List<Integer> branchIds) {
        this.branchIds = branchIds;
    }
}
