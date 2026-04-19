package migrator.issues;

import java.sql.*;
import java.util.*;
import java.util.regex.*;

import oracle.jdbc.pool.OracleDataSource;
import utils.OracleConnection;

import oracle.jdbc.pool.OracleDataSource;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

public class ProcedureCall implements Issue {
    
    private final Set<String> procedureCache = new HashSet<>();
    private final Map<String, SubprogramInfo> subprogramCache = new HashMap<>();
    private boolean debug = false;
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        return fixProcedureCalls(content);
    }
    
    private String fixProcedureCalls(String content) {
        String[] lines = content.split("\n", -1);
        List<String> resultLines = new ArrayList<>();
        boolean insideBlockComment = false;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            
            if (insideBlockComment) {
                resultLines.add(line);
                if (line.contains("*/")) insideBlockComment = false;
                continue;
            }
            
            int blockCommentStart = line.indexOf("/*");
            if (blockCommentStart != -1) {
                int blockCommentEnd = line.indexOf("*/", blockCommentStart + 2);
                if (blockCommentEnd == -1) {
                    insideBlockComment = true;
                    resultLines.add(line);
                    continue;
                }
            }
            
            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }
            
            if (isProcedureCallStart(line)) {
                if (isInsideComment(line, blockCommentStart)) {
                    resultLines.add(line);
                    continue;
                }
                
                if (isInExpression(line)) {
                    resultLines.add(line);
                    continue;
                }
                
                // Собираем полный вызов (может быть на нескольких строках)
                StringBuilder fullCall = new StringBuilder(line);
                int j = i + 1;
                if (!isCallComplete(line)) {
                    while (j < lines.length) {
                        String nextLine = lines[j];
                        fullCall.append("\n").append(nextLine);
                        if (isCallComplete(nextLine) || nextLine.trim().endsWith(");")) {
                            j++;
                            break;
                        }
                        j++;
                    }
                }
                
                String callText = fullCall.toString();
                String fixedCall = addCallToProcedureCall(callText);
                if (fixedCall != null && !fixedCall.equals(callText)) {
                    String[] callLines = fixedCall.split("\n", -1);
                    for (String callLine : callLines) resultLines.add(callLine);
                    i = j - 1;
                    continue;
                }
            }
            resultLines.add(line);
        }
        return String.join("\n", resultLines);
    }
    
    private boolean isInsideComment(String line, int blockCommentStart) {
        int callStart = line.indexOf("(");
        if (callStart == -1) return false;
        int lastBlockStart = line.lastIndexOf("/*", callStart);
        if (lastBlockStart != -1) {
            int lastBlockEnd = line.lastIndexOf("*/", callStart);
            if (lastBlockEnd == -1 || lastBlockEnd < lastBlockStart) return true;
        }
        return false;
    }
    
    private boolean isProcedureCallStart(String line) {
        String trimmed = line.trim();
        return trimmed.matches("^[a-zA-Z_][a-zA-Z0-9_$#]*\\.[a-zA-Z_][a-zA-Z0-9_$#]*(\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?\\s*\\(.*");
    }
    
    private boolean isCallComplete(String line) {
        String trimmed = line.trim();
        int semicolonPos = trimmed.indexOf(';');
        if (semicolonPos > 0) {
            String beforeSemicolon = trimmed.substring(0, semicolonPos);
            int commentPos = beforeSemicolon.indexOf("--");
            if (commentPos == -1) return true;
        }
        return trimmed.endsWith(");");
    }
    
    private boolean isInExpression(String line) {
        String trimmed = line.trim();
        return trimmed.matches("^.*(?i)(:=|SELECT\\s+|INTO\\s+|RETURN\\s+|VALUES\\s*\\()\\s*.*") ||
               trimmed.startsWith(":=");
    }
    
    private String addCallToProcedureCall(String callText) {
        Pattern namePattern = Pattern.compile("^\\s*([a-zA-Z_][a-zA-Z0-9_$#.]*)\\s*\\(");
        Matcher nameMatcher = namePattern.matcher(callText);
        if (nameMatcher.find()) {
            String fullName = nameMatcher.group(1);
            List<String> actualArgs = extractArgumentList(callText);
            SubprogramInfo info = getSubprogramInfo(fullName);
            if (info != null && "PROCEDURE".equals(info.subprogramType)) {
                // Сравниваем количество аргументов
                if (actualArgs.size() == info.parameters.size()) {
                    String indent = "";
                    int firstNonSpace = 0;
                    while (firstNonSpace < callText.length() && callText.charAt(firstNonSpace) == ' ') {
                        indent += " ";
                        firstNonSpace++;
                    }
                    return indent + "CALL " + callText.substring(firstNonSpace);
                } else if (debug) {
                    System.err.println("Argument count mismatch for " + fullName +
                            ": expected " + info.parameters.size() + ", got " + actualArgs.size());
                }
            }
        }
        return callText;
    }
    
    /**
     * Извлекает список аргументов из текста вызова.
     * Учитывает вложенные скобки и строковые литералы.
     */
    private List<String> extractArgumentList(String callText) {
        List<String> args = new ArrayList<>();
        int startParams = callText.indexOf('(');
        if (startParams == -1) return args;
        
        int endParams = findMatchingParen(callText, startParams);
        if (endParams == -1) return args;
        
        String paramsStr = callText.substring(startParams + 1, endParams).trim();
        if (paramsStr.isEmpty()) return args;
        
        // Разбиваем по запятым, не учитывая запятые внутри вложенных скобок и строк
        StringBuilder current = new StringBuilder();
        int parenLevel = 0;
        boolean inString = false;
        char stringDelimiter = '\0';
        
        for (int i = 0; i < paramsStr.length(); i++) {
            char c = paramsStr.charAt(i);
            if (!inString && (c == '\'' || c == '"')) {
                inString = true;
                stringDelimiter = c;
                current.append(c);
                continue;
            }
            if (inString && c == stringDelimiter) {
                // Проверяем, не экранирован ли
                if (i > 0 && paramsStr.charAt(i-1) != '\\') {
                    inString = false;
                }
                current.append(c);
                continue;
            }
            if (!inString) {
                if (c == '(') parenLevel++;
                else if (c == ')') parenLevel--;
                else if (c == ',' && parenLevel == 0) {
                    args.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            args.add(current.toString().trim());
        }
        return args;
    }
    
    private int findMatchingParen(String s, int openPos) {
        int count = 1;
        for (int i = openPos + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') count++;
            else if (c == ')') {
                count--;
                if (count == 0) return i;
            }
        }
        return -1;
    }
    
    private SubprogramInfo getSubprogramInfo(String fullName) {
        String upperName = fullName.toUpperCase();
        if (subprogramCache.containsKey(upperName)) {
            return subprogramCache.get(upperName);
        }
        
        String[] parts = upperName.split("\\.");
        if (parts.length != 2) return null;
        String packageName = parts[0];
        String subprogramName = parts[1];
        
        // Исправленный SQL запрос
        String sql = 
            "SELECT " +
            "   a.PACKAGE_NAME, " +
            "   a.OBJECT_NAME AS subprogram_name, " +
            "   a.SUBPROGRAM_ID, " +
            "   CASE " +
            "       WHEN EXISTS (SELECT 1 FROM ALL_ARGUMENTS a2 " +
            "                    WHERE a2.OBJECT_ID = a.OBJECT_ID " +
            "                      AND a2.SUBPROGRAM_ID = a.SUBPROGRAM_ID " +
            "                      AND a2.POSITION = 0 " +
            "                      AND a2.ARGUMENT_NAME IS NULL) " +
            "       THEN 'FUNCTION' " +
            "       ELSE 'PROCEDURE' " +
            "   END AS subprogram_type, " +
            "   a.ARGUMENT_NAME, " +
            "   a.POSITION, " +
            "   a.IN_OUT, " +
            "   a.DATA_TYPE, " +
            "   a.DATA_LENGTH, " +
            "   a.CHAR_LENGTH, " +
            "   a.DATA_PRECISION, " +
            "   a.DATA_SCALE " +
            "FROM ALL_ARGUMENTS a " +
            "WHERE a.PACKAGE_NAME = ? " +
            "  AND a.OBJECT_NAME = ? " +
            "  AND a.DATA_LEVEL = 0 " +
            "ORDER BY a.SUBPROGRAM_ID, a.POSITION";
        
        Map<Integer, SubprogramInfo> infoMap = new HashMap<>();
        try (Connection conn = OracleConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, packageName);
            pstmt.setString(2, subprogramName);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int subId = rs.getInt("SUBPROGRAM_ID");
                    SubprogramInfo info = infoMap.get(subId);
                    if (info == null) {
                        info = new SubprogramInfo();
                        info.packageName = rs.getString("PACKAGE_NAME");
                        info.subprogramName = rs.getString("subprogram_name");
                        info.subprogramId = subId;
                        info.subprogramType = rs.getString("subprogram_type");
                        info.parameters = new ArrayList<>();
                        infoMap.put(subId, info);
                    }
                    String argName = rs.getString("ARGUMENT_NAME");
                    int position = rs.getInt("POSITION");
                    if (position == 0 && "FUNCTION".equals(info.subprogramType)) {
                        info.returnType = rs.getString("DATA_TYPE");
                        info.returnLength = rs.getInt("DATA_LENGTH");
                    } else if (argName != null) {
                        Parameter param = new Parameter();
                        param.name = argName;
                        param.position = position;
                        param.inOut = rs.getString("IN_OUT");
                        param.dataType = rs.getString("DATA_TYPE");
                        param.dataLength = rs.getInt("DATA_LENGTH");
                        param.charLength = rs.getInt("CHAR_LENGTH");
                        param.precision = rs.getInt("DATA_PRECISION");
                        param.scale = rs.getInt("DATA_SCALE");
                        info.parameters.add(param);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Ошибка при получении информации о подпрограмме: " + fullName);
            e.printStackTrace();
            return null;
        }
        
        // В пакете может быть несколько перегруженных подпрограмм (разные SUBPROGRAM_ID)
        // Для простоты берём первую, либо можно добавить логику выбора по количеству аргументов
        SubprogramInfo result = null;
        for (SubprogramInfo info : infoMap.values()) {
            if (result == null) result = info;
            // Можно реализовать выбор по сигнатуре, но для большинства случаев достаточно первого
        }
        if (result != null) {
            subprogramCache.put(upperName, result);
            if ("PROCEDURE".equals(result.subprogramType)) {
                procedureCache.add(upperName);
            }
        }
        return result;
    }
    
    // Вспомогательные классы
    public static class SubprogramInfo {
        public String packageName;
        public String subprogramName;
        public int subprogramId;
        public String subprogramType;
        public String returnType;
        public int returnLength;
        public List<Parameter> parameters;
        
        @Override
        public String toString() {
            return String.format("%s.%s [%s]", packageName, subprogramName, subprogramType);
        }
    }
    
    public static class Parameter {
        public String name;
        public int position;
        public String inOut;
        public String dataType;
        public int dataLength;
        public int charLength;
        public int precision;
        public int scale;
        
        @Override
        public String toString() {
            return String.format("%s %s %s", inOut, name, dataType);
        }
    }
}