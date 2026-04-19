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

import oracle.jdbc.pool.OracleDataSource;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

public class ProcedureCall implements Issue {
    
    private final Set<String> procedureCache = new HashSet<>();
    private final Map<String, SubprogramInfo> subprogramCache = new HashMap<>();
    private boolean debug = false;
    
    // Параметры подключения – вынесите в конфиг при необходимости
    private static final String DB_URL = "jdbc:oracle:thin:@localhost:1521/ORCLPDB1";
    private static final String DB_USER = "CMOP";
    private static final String DB_PASSWORD = "CMOP";
    
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
            SubprogramInfo info = findBestMatchingSubprogram(fullName, actualArgs);
            if (info != null && "PROCEDURE".equals(info.subprogramType)) {
                String indent = "";
                int firstNonSpace = 0;
                while (firstNonSpace < callText.length() && callText.charAt(firstNonSpace) == ' ') {
                    indent += " ";
                    firstNonSpace++;
                }
                return indent + "CALL " + callText.substring(firstNonSpace);
            } else if (debug && info == null) {
                System.err.println("No matching subprogram found for " + fullName);
            }
        }
        return callText;
    }
    
    /**
     * Выбирает подпрограмму, наиболее подходящую по количеству переданных аргументов.
     * Учитывает параметры с DEFAULT (их можно не указывать).
     */
    private SubprogramInfo findBestMatchingSubprogram(String fullName, List<String> actualArgs) {
        String upperName = fullName.toUpperCase();
        // Сначала пробуем получить из кеша (уже загружены все перегрузки)
        if (!subprogramCache.containsKey(upperName)) {
            loadAllSubprogramsForName(fullName);
        }
        
        List<SubprogramInfo> overloads = new ArrayList<>();
        for (Map.Entry<String, SubprogramInfo> entry : subprogramCache.entrySet()) {
            if (entry.getKey().startsWith(upperName + "#") || entry.getKey().equals(upperName)) {
                overloads.add(entry.getValue());
            }
        }
        
        if (overloads.isEmpty()) return null;
        
        int actualCount = actualArgs.size();
        // Ищем точное совпадение по количеству обязательных параметров (без DEFAULT)
        for (SubprogramInfo info : overloads) {
            int requiredCount = 0;
            for (Parameter p : info.parameters) {
                if (!p.hasDefault) requiredCount++;
            }
            if (actualCount == requiredCount) {
                return info;
            }
        }
        // Если точного нет, ищем подпрограмму, у которой actualCount между required и total
        for (SubprogramInfo info : overloads) {
            int required = (int) info.parameters.stream().filter(p -> !p.hasDefault).count();
            int total = info.parameters.size();
            if (actualCount >= required && actualCount <= total) {
                return info;
            }
        }
        // Иначе возвращаем первую (как fallback)
        return overloads.get(0);
    }
    
    /**
     * Загружает ВСЕ подпрограммы с данным именем (все перегрузки) и сохраняет в кеш.
     */
    private void loadAllSubprogramsForName(String fullName) {
        String upperName = fullName.toUpperCase();
        String[] parts = upperName.split("\\.");
        if (parts.length != 2) return;
        String packageName = parts[0];
        String subprogramName = parts[1];
        
        Map<Integer, SubprogramInfo> infoMap = new HashMap<>();
        
        // 1. Загружаем информацию из ALL_ARGUMENTS (работает даже в старых версиях)
        String sqlArgs = 
            "SELECT " +
            "   PACKAGE_NAME, " +
            "   OBJECT_NAME AS subprogram_name, " +
            "   SUBPROGRAM_ID, " +
            "   ARGUMENT_NAME, " +
            "   POSITION, " +
            "   IN_OUT, " +
            "   DATA_TYPE, " +
            "   DATA_LENGTH, " +
            "   CHAR_LENGTH, " +
            "   DATA_PRECISION, " +
            "   DATA_SCALE, " +
            "   DEFAULT_VALUE " +
            "FROM ALL_ARGUMENTS " +
            "WHERE PACKAGE_NAME = ? " +
            "  AND OBJECT_NAME = ? " +
            "  AND DATA_LEVEL = 0 " +
            "ORDER BY SUBPROGRAM_ID, POSITION";
        
        boolean hasAnyRows = false;
        try (Connection conn = createNewConnection();
             PreparedStatement pstmt = conn.prepareStatement(sqlArgs)) {
            
            pstmt.setString(1, packageName);
            pstmt.setString(2, subprogramName);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    hasAnyRows = true;
                    int subId = rs.getInt("SUBPROGRAM_ID");
                    SubprogramInfo info = infoMap.get(subId);
                    if (info == null) {
                        info = new SubprogramInfo();
                        info.packageName = rs.getString("PACKAGE_NAME");
                        info.subprogramName = rs.getString("subprogram_name");
                        info.subprogramId = subId;
                        info.subprogramType = "PROCEDURE"; // временно, будет уточнено ниже
                        info.parameters = new ArrayList<>();
                        infoMap.put(subId, info);
                    }
                    
                    String argName = rs.getString("ARGUMENT_NAME");
                    int position = rs.getInt("POSITION");
                    String defaultValue = rs.getString("DEFAULT_VALUE");
                    
                    if (position == 0 && (argName == null || argName.isEmpty())) {
                        // Это возвращаемое значение функции
                        info.subprogramType = "FUNCTION";
                        info.returnType = rs.getString("DATA_TYPE");
                        info.returnLength = rs.getInt("DATA_LENGTH");
                    } else if (argName != null && !argName.isEmpty()) {
                        Parameter param = new Parameter();
                        param.name = argName;
                        param.position = position;
                        param.inOut = rs.getString("IN_OUT");
                        param.dataType = rs.getString("DATA_TYPE");
                        param.dataLength = rs.getInt("DATA_LENGTH");
                        param.charLength = rs.getInt("CHAR_LENGTH");
                        param.precision = rs.getInt("DATA_PRECISION");
                        param.scale = rs.getInt("DATA_SCALE");
                        param.hasDefault = (defaultValue != null && !defaultValue.trim().isEmpty());
                        info.parameters.add(param);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Ошибка при загрузке аргументов: " + e.getMessage());
            return;
        }
        
        // 2. Если нет записей в ALL_ARGUMENTS – подпрограмма без параметров
        if (!hasAnyRows) {
            // Определяем тип через USER_SOURCE
            String sqlType = 
                "SELECT CASE " +
                "   WHEN EXISTS (SELECT 1 FROM USER_SOURCE " +
                "                WHERE NAME = ? " +
                "                  AND TYPE = 'PACKAGE' " +
                "                  AND UPPER(TEXT) LIKE '%FUNCTION ' || UPPER(?) || '%' " +
                "                  AND INSTR(UPPER(TEXT), 'FUNCTION ' || UPPER(?)) > 0) " +
                "   THEN 'FUNCTION' ELSE 'PROCEDURE' END AS subprogram_type " +
                "FROM DUAL";
            
            String subprogramType = "PROCEDURE"; // по умолчанию
            try (Connection conn = createNewConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sqlType)) {
                
                pstmt.setString(1, packageName);
                pstmt.setString(2, subprogramName);
                pstmt.setString(3, subprogramName);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        subprogramType = rs.getString("subprogram_type");
                    }
                }
            } catch (SQLException e) {
                System.err.println("Ошибка при определении типа: " + e.getMessage());
                // Оставляем PROCEDURE по умолчанию
            }
            
            SubprogramInfo info = new SubprogramInfo();
            info.packageName = packageName;
            info.subprogramName = subprogramName;
            info.subprogramId = 1;
            info.subprogramType = subprogramType;
            info.parameters = new ArrayList<>();
            infoMap.put(1, info);
        }
        
        // 3. Сохраняем в кеш
        for (SubprogramInfo info : infoMap.values()) {
            String key = fullName.toUpperCase() + "#" + info.subprogramId;
            subprogramCache.put(key, info);
            if (!subprogramCache.containsKey(fullName.toUpperCase())) {
                subprogramCache.put(fullName.toUpperCase(), info);
            }
            if ("PROCEDURE".equals(info.subprogramType)) {
                procedureCache.add(fullName.toUpperCase());
            }
        }
    }
    
    private SubprogramInfo getSubprogramInfo(String fullName) {
        String upperName = fullName.toUpperCase();
        if (subprogramCache.containsKey(upperName)) {
            return subprogramCache.get(upperName);
        }
        loadAllSubprogramsForName(fullName);
        return subprogramCache.get(upperName);
    }
    
    /**
     * Создаёт НОВОЕ соединение с БД (решает проблему закрытых соединений).
     */
    private Connection createNewConnection() throws SQLException {
        OracleDataSource ods = new OracleDataSource();
        ods.setURL(DB_URL);
        ods.setUser(DB_USER);
        ods.setPassword(DB_PASSWORD);
        return ods.getConnection();
    }
    
    // ---------- Вспомогательные методы для извлечения аргументов (без изменений) ----------
    private List<String> extractArgumentList(String callText) {
        List<String> args = new ArrayList<>();
        int startParams = callText.indexOf('(');
        if (startParams == -1) return args;
        int endParams = findMatchingParen(callText, startParams);
        if (endParams == -1) return args;
        String paramsStr = callText.substring(startParams + 1, endParams).trim();
        if (paramsStr.isEmpty()) return args;
        
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
    
    // ---------- Внутренние классы ----------
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
        public boolean hasDefault;
        
        @Override
        public String toString() {
            return String.format("%s %s %s%s", inOut, name, dataType, hasDefault ? " DEFAULT" : "");
        }
    }
}