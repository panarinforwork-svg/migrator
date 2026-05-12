package migrator.issues;

public class DblinkFunctionConverter implements Issue {

    private static final boolean LOGGING_ENABLED = true;

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        return processOutsideStrings(content);
    }

    private String processOutsideStrings(String content) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inDollarQuote = false;
        String currentDollarTag = null;
        int lastPos = 0;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (!inString && !inDollarQuote && c == '$' && i + 1 < content.length()) {
                int end = content.indexOf('$', i + 1);
                if (end > i + 1) {
                    currentDollarTag = content.substring(i, end + 1);
                    inDollarQuote = true;
                    result.append(content, lastPos, i);
                    lastPos = i;
                    continue;
                }
            }
            if (inDollarQuote && i + currentDollarTag.length() - 1 < content.length()) {
                String possibleTag = content.substring(i, i + currentDollarTag.length());
                if (possibleTag.equals(currentDollarTag)) {
                    inDollarQuote = false;
                    currentDollarTag = null;
                }
                continue;
            }
            if (!inDollarQuote && c == '\'' && (i == 0 || content.charAt(i - 1) != '\\')) {
                if (!inString) {
                    String beforeString = content.substring(lastPos, i);
                    result.append(processFunctionCalls(beforeString));
                    lastPos = i;
                    inString = true;
                } else {
                    result.append(content, lastPos, i + 1);
                    lastPos = i + 1;
                    inString = false;
                }
            }
        }
        if (lastPos < content.length()) {
            String remaining = content.substring(lastPos);
            if (!inString && !inDollarQuote) {
                result.append(processFunctionCalls(remaining));
            } else {
                result.append(remaining);
            }
        }
        return result.toString();
    }

    private String processFunctionCalls(String sql) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = sql.length();

        while (i < len) {
            int atPos = sql.indexOf('@', i);
            if (atPos == -1) {
                result.append(sql.substring(i));
                break;
            }

            // Находим начало имени перед '@'
            int startName = atPos - 1;
            while (startName >= i && (Character.isLetterOrDigit(sql.charAt(startName)) ||
                   sql.charAt(startName) == '_' || sql.charAt(startName) == '.' || sql.charAt(startName) == '$')) {
                startName--;
            }
            startName++;
            String leftPart = sql.substring(startName, atPos);
            if (leftPart.trim().isEmpty()) {
                result.append(sql.substring(i, atPos + 1));
                i = atPos + 1;
                continue;
            }

            // Пропускаем пробелы после '@' и находим dblink имя
            int afterAt = atPos + 1;
            while (afterAt < len && Character.isWhitespace(sql.charAt(afterAt))) afterAt++;
            int dblinkStart = afterAt;
            while (afterAt < len && (Character.isLetterOrDigit(sql.charAt(afterAt)) ||
                   sql.charAt(afterAt) == '_' || sql.charAt(afterAt) == '.')) afterAt++;
            String dblinkName = sql.substring(dblinkStart, afterAt);
            if (dblinkName.isEmpty()) {
                result.append(sql.substring(i, afterAt));
                i = afterAt;
                continue;
            }

            // Проверка DML контекста
            int searchStart = Math.max(0, sql.lastIndexOf(';', startName) + 1);
            if (searchStart < startName - 200) searchStart = startName - 200;
            if (searchStart < 0) searchStart = 0;
            
            String context = sql.substring(searchStart, startName).toLowerCase();
            
            boolean isDML = false;
            if (context.contains("insert into") || 
                context.contains("update ") ||
                context.contains("delete from") ||
                context.matches(".*\\bfrom\\s+\\S*$") ||
                context.matches(".*\\bjoin\\s+\\S*$")) {
                isDML = true;
            }
            
            if (isDML) {
                result.append(sql.substring(i, afterAt));
                i = afterAt;
                if (LOGGING_ENABLED) {
                    System.out.println("[SKIP] TABLE in DML: " + leftPart + "@" + dblinkName);
                }
                continue;
            }

            // Пропускаем email-адреса
            if (leftPart.matches("^[a-zA-Z0-9._%+-]+$") && dblinkName.matches("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")) {
                result.append(sql.substring(i, afterAt));
                i = afterAt;
                continue;
            }

            // Проверяем, есть ли скобки или ';' после dblink
            int nextPos = afterAt;
            while (nextPos < len && Character.isWhitespace(sql.charAt(nextPos))) nextPos++;
            
            if (nextPos >= len || (sql.charAt(nextPos) != '(' && sql.charAt(nextPos) != ';')) {
                result.append(sql.substring(i, afterAt));
                i = afterAt;
                continue;
            }

            // Обработка вызова
            String params = "";
            int endOfCall;
            boolean hasSemicolon = false;
            
            if (nextPos < len && sql.charAt(nextPos) == '(') {
                int openParen = nextPos;
                int depth = 1;
                int pos = openParen + 1;
                while (pos < len && depth > 0) {
                    char ch = sql.charAt(pos);
                    if (ch == '(') depth++;
                    else if (ch == ')') depth--;
                    if (depth == 0) break;
                    pos++;
                }
                if (depth != 0) {
                    result.append(sql.substring(i, openParen + 1));
                    i = openParen + 1;
                    continue;
                }
                int closeParen = pos;
                params = sql.substring(openParen + 1, closeParen);
                endOfCall = closeParen + 1;
                while (endOfCall < len && Character.isWhitespace(sql.charAt(endOfCall))) endOfCall++;
                if (endOfCall < len && sql.charAt(endOfCall) == ';') {
                    hasSemicolon = true;
                    endOfCall++;
                }
            } else if (nextPos < len && sql.charAt(nextPos) == ';') {
                params = "";
                endOfCall = nextPos + 1;
                hasSemicolon = true;
            } else {
                result.append(sql.substring(i, afterAt));
                i = afterAt;
                continue;
            }

            // Определяем, функция или процедура
            // Берем контекст от последней точки с запятой до вызова
            int lastSemicolon = sql.lastIndexOf(';', startName);
            int funcCheckStart = Math.max(lastSemicolon + 1, startName - 50);
            if (funcCheckStart < 0) funcCheckStart = 0;
            String before = sql.substring(funcCheckStart, startName);
            
            // Очищаем от whitespace в конце
            before = before.replaceAll("\\s+$", "");
            
            boolean isFunction = before.endsWith(":=") || 
                                 before.toLowerCase().endsWith("into") ||
                                 before.toLowerCase().endsWith("return");
            
            String[] parts = leftPart.split("\\.");
            String functionName = parts[parts.length - 1];
            String packageName = parts.length > 1 ? parts[parts.length - 2] : null;
            String safeDblink = dblinkName.replace('.', '_');

            String replacement;
            if (isFunction) {
                String remoteCall;
                if (packageName != null) {
                    remoteCall = String.format("SELECT %s.%s(%s) FROM DUAL", packageName, functionName, params);
                } else {
                    remoteCall = String.format("SELECT %s(%s) FROM DUAL", functionName, params);
                }
                remoteCall = remoteCall.replace("()", "");
                String escapedRemote = remoteCall.replace("'", "''");
                replacement = String.format(
                    "(SELECT result FROM dblink('%s_server', '%s') AS t(result TEXT))",
                    safeDblink, escapedRemote
                );
                if (hasSemicolon) replacement += ";";
            } else {
                String remoteCall;
                if (packageName != null) {
                    remoteCall = String.format("BEGIN %s.%s(%s); END;", packageName, functionName, params);
                } else {
                    remoteCall = String.format("BEGIN %s(%s); END;", functionName, params);
                }
                remoteCall = remoteCall.replace("()", "");
                String escapedRemote = remoteCall.replace("'", "''");
                replacement = String.format(
                    "SELECT * FROM dblink('%s_server', '%s') AS t(result TEXT);",
                    safeDblink, escapedRemote
                );
            }

            if (LOGGING_ENABLED) {
                System.out.println("[CONVERT] " + leftPart + "@" + dblinkName + " -> " + (isFunction ? "FUNCTION" : "PROCEDURE"));
            }

            result.append(sql.substring(i, startName));
            result.append(replacement);
            i = endOfCall;
        }
        return result.toString();
    }
}