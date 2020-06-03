package org.evosuite.defectprediction.method;

import org.apache.commons.lang3.StringUtils;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class MethodUtils {

    public static String convertMethodName(String methodEvoFormat, String className) {
        List<String> parameters = extractParameters(methodEvoFormat);
        List<String> convertedParameters = convertParameters(parameters);

        String returnType = extractReturnType(methodEvoFormat);
        String convertedReturnType = convertReturnType(returnType);

        /*String simpleMethodName = extractSimpleName(methodEvoFormat);
        if (simpleMethodName.equals("<init>")) {
            simpleMethodName = className.substring(className.lastIndexOf(".") + 1);
        }*/

        String simpleFqMethodName = extractSimpleName(methodEvoFormat);
        simpleFqMethodName = handleConstructorNames(simpleFqMethodName, className);

        simpleFqMethodName = separateClassAndMethodNames(simpleFqMethodName);

        return formConvertedMethodName(simpleFqMethodName, convertedParameters, convertedReturnType);
    }

    private static String separateClassAndMethodNames(String simpleFqMethodName) {
        String fullClassName = simpleFqMethodName.substring(0, simpleFqMethodName.lastIndexOf("."));
        String methodName = simpleFqMethodName.substring(simpleFqMethodName.lastIndexOf(".") + 1);

        return fullClassName + ":" + methodName;
    }

    private static String handleConstructorNames(String simpleFqMethodName, String className) {
        if (simpleFqMethodName.endsWith("<init>")) {
            String constructorName;
            if (simpleFqMethodName.contains("$")) {
                constructorName = simpleFqMethodName.substring(simpleFqMethodName.indexOf("$") + 1, simpleFqMethodName.lastIndexOf("."));
            } else {
                constructorName = className.substring(className.lastIndexOf(".") + 1);
            }
            simpleFqMethodName = simpleFqMethodName.replace("<init>", constructorName);
        }

        return simpleFqMethodName;
    }

    private static String formConvertedMethodName(String simpleMethodName, List<String> parameters, String returnType) {
        String convertedMethod = simpleMethodName + '(';

        for (String parameter : parameters) {
            convertedMethod += parameter + ";";
        }
        convertedMethod += ")";

        convertedMethod += returnType;
        convertedMethod += ":";

        return convertedMethod;
    }

    private static String extractSimpleName(String methodEvoFormat) {
        return methodEvoFormat.substring(0, methodEvoFormat.indexOf("("));
    }

    private static String convertReturnType(String returnType) {
        return getEquivalentParameter(returnType);
    }

    private static String extractReturnType(String methodEvoFormat) {
        if (methodEvoFormat.endsWith(";")) {
            return methodEvoFormat.substring(methodEvoFormat.indexOf(")") + 1, methodEvoFormat.length() - 1);
        } else {
            return methodEvoFormat.substring(methodEvoFormat.indexOf(")") + 1);
        }
    }

    private static List<String> extractParameters(String methodEvoFormat) {
        List<String> parameters = new ArrayList<>();

        String paramStr = methodEvoFormat.substring(methodEvoFormat.indexOf("(") + 1, methodEvoFormat.indexOf(")"));

        if (paramStr.isEmpty()) {
            return parameters;
        }

        int paramStrLength = paramStr.length();

        for (int index = 0; index < paramStrLength; index++) {
            char currentChar = paramStr.charAt(index);

            if (isPrimitiveType(currentChar)) {
                parameters.add(Character.toString(currentChar));
                continue;
            }

            if (currentChar == '[') {   // TODO: [[ <-> [][]
                StringBuilder currentParam = new StringBuilder();
                currentParam.append(currentChar);
                index = handleParameterWithArray(paramStr, index, parameters, currentParam);
                continue;

                /*char nextChar = paramStr.charAt(index + 1);
                if (isPrimitiveType(nextChar)) {
                    char[] parameter = {currentChar, nextChar};
                    parameters.add(new String(parameter));
                    index++;
                    continue;
                } else if (nextChar == 'L') {
                    int fqClassParamEndIndex = paramStr.indexOf(';', index + 1);
                    parameters.add(paramStr.substring(index, fqClassParamEndIndex));
                    index = fqClassParamEndIndex;
                    continue;
                } else {
                    LoggingUtils.getEvoLogger().error("Unexpected character after [ : " + nextChar);
                    continue;
                }*/
            }

            if (currentChar == 'L') {
                int fqClassParamEndIndex = paramStr.indexOf(';', index);
                parameters.add(paramStr.substring(index, fqClassParamEndIndex));
                index = fqClassParamEndIndex;
                continue;
            }

            LoggingUtils.getEvoLogger().error("Unidentified character in the signature: " + currentChar);
        }

        return parameters;
    }

    private static int handleParameterWithArray(String paramStr, int currentIndex, List<String> parameters, StringBuilder currentParam) {
        char nextChar = paramStr.charAt(currentIndex + 1);
        if (isPrimitiveType(nextChar)) {
            StringBuilder parameter = new StringBuilder();
            parameter.append(currentParam);
            parameter.append(nextChar);
            parameters.add(new String(parameter));

            return currentIndex + 1;
        } else if (nextChar == 'L') {
            int fqClassParamEndIndex = paramStr.indexOf(';', currentIndex + 1);

            StringBuilder parameter = new StringBuilder();
            parameter.append(currentParam);
            parameter.append(paramStr.substring(currentIndex + 1, fqClassParamEndIndex));
            parameters.add(new String(parameter));

            return fqClassParamEndIndex;
        } else if (nextChar == '[') {
            return handleParameterWithArray(paramStr, currentIndex + 1, parameters, currentParam.append(nextChar));
        } else {
            LoggingUtils.getEvoLogger().error("Unexpected character after [ : " + nextChar);
            return currentIndex;
        }
    }

    private static List<String> convertParameters(List<String> parameters) {
        List<String> convertedParameters = new ArrayList<>();

        for (String parameter : parameters) {
            convertedParameters.add(getEquivalentParameter(parameter));
        }

        return convertedParameters;
    }

    private static String getEquivalentParameter(String parameter) {
        if (parameter.length() == 1) {
            return convertPrimitiveType(parameter.charAt(0));
        }

        if (parameter.contains("[")) {
            int arrayDimension = countOccurrences(parameter, '[');
            if (arrayDimension > 0) {
                int parameterTypeLength = parameter.length() - arrayDimension;
                if (parameterTypeLength == 1) {
                    String convertedPrimitiveType = convertPrimitiveType(parameter.charAt(parameter.length() - 1));
                    for (int iter = 0; iter < arrayDimension; iter++) {
                        convertedPrimitiveType += "[]";
                    }
                    return convertedPrimitiveType;
                } else {
                    String simpleClass = extractSimpleClassName(parameter);
                    for (int iter = 0; iter < arrayDimension; iter++) {
                        simpleClass += "[]";
                    }
                    return simpleClass;
                }
            }
        }

        /*if (parameter.charAt(0) == '[') {   // TODO: [[ <-> [][]
            if (parameter.length() == 2) {
                String convertedPrimitiveType = convertPrimitiveType(parameter.charAt(1));
                return convertedPrimitiveType + "[]";
            } else {
                String simpleClass = parameter.substring(parameter.lastIndexOf('/') + 1);
                return simpleClass + "[]";
            }
        }*/

        if (parameter.charAt(0) == 'L') {
            return extractSimpleClassName(parameter);
        }

        LoggingUtils.getEvoLogger().error("Unidentified parameter: " + parameter);
        return "";
    }

    private static String extractSimpleClassName(String fqClassName) {
        String simpleClassName = fqClassName.substring(fqClassName.lastIndexOf('/') + 1);
        if (simpleClassName.contains("$")) {
            simpleClassName = simpleClassName.substring(simpleClassName.indexOf('$') + 1);
        }

        return simpleClassName;
    }

    private static int countOccurrences(String parameter, char searchKey) {
        int count = 0;
        for (int index = 0; index < parameter.length(); index++) {
            if (parameter.charAt(index) == searchKey) {
                count++;
            }
        }
        return count;
    }

    private static String convertPrimitiveType(char type) {
        switch (type) {
            case 'Z':
                return "boolean";
            case 'B':
                return "byte";
            case 'C':
                return "char";
            case 'S':
                return "short";
            case 'I':
                return "int";
            case 'J':
                return "long";
            case 'F':
                return "float";
            case 'D':
                return "double";
            case 'V':
                return "";
            default:
                LoggingUtils.getEvoLogger().error("Unidentified primitive type: " + type);
                return "";
        }
    }

    private static boolean isPrimitiveType(char type) {
        switch (type) {
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I':
            case 'J':
            case 'F':
            case 'D':
                return true;
            default:
                return false;
        }
    }
}
