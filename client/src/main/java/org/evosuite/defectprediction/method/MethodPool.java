package org.evosuite.defectprediction.method;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageSuiteFitness;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.utils.LoggingUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

public class MethodPool {

    private Map<String, Method> methods = new HashMap<>();
    private String className;

    private double defaultWeight;

    private int totalNumBranches = 0;
    private double scaleDownFactor = 0.0;

    //  org.apache.commons.lang.math.NumberUtils.min(SSS)S -> org.apache.commons.lang.math.NumberUtils:min(short;short;short;)short:
    private Map<String, String> equivalentMethodNames = new HashMap<>();

    private static Map<String, MethodPool> instanceMap = new HashMap<String, MethodPool>();

    public MethodPool(String className) {
        this.className = className;
    }

    public void loadDefectScores() {
        String defectScoresFilename = Properties.DP_DIR + "/" + this.className + ".csv";
        this.methods = readDefectScores(defectScoresFilename);
    }

    public static MethodPool getInstance(String className) {
        if (!instanceMap.containsKey(className)) {
            // since method coverage fitness function (MethodCoverageTestFitness) stores the inner-classes as individual
            // classes, let's check if the instanceMap contains the outer-most class and return that
            for (String instanceName : instanceMap.keySet()) {
                if (className.startsWith(instanceName + ".") || className.startsWith(instanceName + "$")) { // className -> inner-class of instanceName
                    return instanceMap.get(instanceName);
                }
            }

            instanceMap.put(className, new MethodPool(className));
        }

        return instanceMap.get(className);
    }

    public Method getMethod(String fqMethodName) throws Exception {
        if (methods.containsKey(fqMethodName)) {
            return methods.get(fqMethodName);
        } else {
            throw new Exception("The method does not exist in the Method Pool (defect scores): " + fqMethodName);
            // LoggingUtils.getEvoLogger().error("The method does not exist in the Method Pool (defect scores): " +
            //         fqMethodName);
            // return null;
        }
    }

    public void updateNumBranches(BranchPool branchPool) {
        List<String> methodsEvoFormat = branchPool.retrieveMethodsInClass(className);

        for (String methodEvoFormat : methodsEvoFormat) {
            int branchCount = branchPool.getBranchCountOfBothTypes(className, methodEvoFormat);
            totalNumBranches += branchCount;

            String fqConvertedMethodName = MethodUtils.convertMethodName(methodEvoFormat, className);
            //String fqConvertedMethodName = className + ":" + convertedMethodName;

            Method method = null;
            try {
                method = getMethod(fqConvertedMethodName);
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (method != null) {
                // int branchCount = branchPool.getBranchCountOfBothTypes(className, methodEvoFormat);
                if (branchCount == 0) {
                    LoggingUtils.getEvoLogger().error("Branch count is zero for the method: " + fqConvertedMethodName);
                }
                method.setNumBranches(branchCount);
                method.setEvoFormatName(methodEvoFormat);

                this.equivalentMethodNames.put(methodEvoFormat, fqConvertedMethodName);

                method.setBranchIds(branchPool.getBranchIdsFor(className, methodEvoFormat));
            }
        }

    }

    private Collection<Method> getMethods() {
        return methods.values();
    }

    public void calculateWeights() {
        double sumDefectScores = calculateDefectScoreSum();
        double normSumDefectScores = 0.0;
        int totalNumBranches = 0;

        for (Method method : getMethods()) {
            method.normalizeDefectScore(sumDefectScores);
            // normSumDefectScores += method.getNormDefectScore() * method.getNumBranches();
            // totalNumBranches += method.getNumBranches();

            normSumDefectScores += method.getNormDefectScore();
            totalNumBranches += 1;
        }

        this.defaultWeight = normSumDefectScores / totalNumBranches;
    }

    private double calculateDefectScoreSum() {
        double sumDefectScores = 0.0;

        for (Method method : getMethods()) {
            // sumDefectScores += method.getNumBranches() * method.getDefectScore();
            sumDefectScores += method.getDefectScore();
        }

        return sumDefectScores;
    }

    public void calculateScaleDownFactor() {
        int totalNumTestsInZeroFront = 0;
        for (Method method : getMethods()) {
            totalNumTestsInZeroFront += (int) (method.getWeight() / this.defaultWeight) * method.getNumBranches();
        }

        this.scaleDownFactor = (double) totalNumTestsInZeroFront / this.totalNumBranches;
    }

    public void calculateArchiveProbabilities() {
        // TODO: As per now, archive probability is equal to the defect score
        for (Method method : getMethods()) {
            method.setArchiveProbability(method.getDefectScore());
        }
    }

    private Map<String, Method> readDefectScores(String filename) {
        Map<String, Method> methodsInFile = new HashMap<>();

        try {
            Scanner s = new Scanner(new File(filename));
            s.nextLine();

            while (s.hasNext()) {
                String row = s.nextLine();
                String[] cells = row.split(",");

                String fqMethodName = cells[0];
                fqMethodName = getFormattedFqMethodName(fqMethodName);

                methodsInFile.put(fqMethodName, new Method(fqMethodName, Double.parseDouble(cells[1])));
            }
            s.close();
        } catch (FileNotFoundException e) {
            LoggingUtils.getEvoLogger().error("The file " + filename + " does not exist");
        }

        return methodsInFile;
    }

    private String getFormattedFqMethodName(String fqMethodName) {
        fqMethodName = fqMethodName.replace(")void:", "):");
        fqMethodName = fqMethodName.replace("...", "[]");
        fqMethodName = fqMethodName.replace("<?>", "");

        List<String> parameters = extractParameters(fqMethodName);

        List<String> convertedParameters = new ArrayList<>();
        for (String parameter : parameters) {
            convertedParameters.add(convertParameter(parameter));
        }

        String returnType = extractReturnType(fqMethodName);
        String convertedReturnType = convertParameter(returnType);

        String simpleFqMethodName = fqMethodName.substring(0, fqMethodName.indexOf("("));

        fqMethodName = formConvertedMethodName(simpleFqMethodName, convertedParameters, convertedReturnType);

        return fqMethodName;
    }

    private String formConvertedMethodName(String simpleMethodName, List<String> parameters, String returnType) {
        String convertedMethod = simpleMethodName + '(';

        for (String parameter : parameters) {
            convertedMethod += parameter + ";";
        }
        convertedMethod += ")";

        convertedMethod += returnType;
        convertedMethod += ":";

        return convertedMethod;
    }

    private String extractReturnType(String fqMethodName) {
        return fqMethodName.substring(fqMethodName.indexOf(')') + 1, fqMethodName.lastIndexOf(':'));
    }

    private String convertParameter(String parameter) {
        if (parameter.contains("<")) {
            parameter = parameter.substring(0, parameter.indexOf('<'));
        }

        return parameter;
    }

    private List<String> extractParameters(String fqMethodName) {
        List<String> parameters = new ArrayList<>();

        String paramStr = fqMethodName.substring(fqMethodName.indexOf("(") + 1, fqMethodName.indexOf(")"));
        if (paramStr.isEmpty()) {
            return parameters;
        }

        int currentBeginIndex = 0;
        for (int index = 0; index < paramStr.length(); index++) {
            if (paramStr.charAt(index) == ';') {
                parameters.add(paramStr.substring(currentBeginIndex, index));
                currentBeginIndex = index + 1;
            }
        }

        return parameters;
    }

    public void calculateNumTestCasesInZeroFront(BranchCoverageSuiteFitness suiteFit) {
        for (Method method : getMethods()) {
            int numTestCasesInZeroFront = (int) (method.getWeight() / this.defaultWeight);
            /*List<Branch> branches = branchPool.getBranchesFor(className, method.getEvoFormatName());
            if (!branches.isEmpty()) {
                for (Branch branch : branches) {
                    suiteFit.setNumTestCasesInZeroFrontFor(branch.getActualBranchId(), numTestCasesInZeroFront);
                }
            } else {    // branchless method
                suiteFit.setNumTestCasesInZeroFrontFor(className + "." + method.getEvoFormatName(), numTestCasesInZeroFront);
            }*/

            List<Integer> branchIds = method.getBranchIds();
            if (!branchIds.isEmpty()) {
                for (Integer branchId : branchIds) {
                    suiteFit.setNumTestCasesInZeroFrontFor(branchId, numTestCasesInZeroFront);
                }
            } else {    // branchless method
                suiteFit.setNumTestCasesInZeroFrontFor(className + "." + method.getEvoFormatName(), numTestCasesInZeroFront);
            }
        }
    }

    public int calculateNumTestCasesInZeroFront(String className, String methodName) {
        Method method = null;
        try {
            method = getMethodsByEvoFormatName(className + "." + methodName);
            int numTestCasesInZeroFront = (int) Math.ceil(((int) (method.getWeight() / this.defaultWeight)) / this.scaleDownFactor);
            return numTestCasesInZeroFront;
            //return numTestCasesInZeroFront > 0 ? 1 : 0;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return 0;
    }

    private Method getMethodsByEvoFormatName(String evoFormatName) throws Exception {
        if (this.equivalentMethodNames.containsKey(evoFormatName)) {
            return getMethod(this.equivalentMethodNames.get(evoFormatName));
        }

        // since method coverage fitness function (MethodCoverageTestFitness) stores the inner-classes as individual
        // classes, let's check if the equivalentMethodNames contains the evoFormatName supplied in the format of
        // MethodCoverageTestFitness (equivalentMethodNames -> package_name.outer_class$inner_class.method_name,
        // evoFormatName sent by MethodCoverageTestFitness -> package_name.outer_class.inner_class.method_name)
        for (String equivalentMethodName : this.equivalentMethodNames.keySet()) {
            if (evoFormatName.equals(equivalentMethodName.replace('$', '.'))) {
                return getMethod(this.equivalentMethodNames.get(equivalentMethodName));
            }
        }

        throw new Exception("Method does not exist in the MethodPool: " + evoFormatName);
    }

    public double getArchiveProbability(String className, String methodName) {
        Method method = null;
        try {
            method = getMethodsByEvoFormatName(className + "." + methodName);
            return method.getArchiveProbability();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return 0.0;
    }

    public boolean isBuggy(String className, String methodName) {
        Method method = null;
        try {
            method = getMethodsByEvoFormatName(className + "." + methodName);
            return method.getDefectScore() > 0 ? true : false;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }
}
