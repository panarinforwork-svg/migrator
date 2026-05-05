package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DblinkToFdw implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Сначала удаляем строковые литералы из обработки
        result = processOutsideStrings(result);
        
        return result;
    }
    
    /**
     * Обрабатывает только текст вне строковых литералов
     */
    private String processOutsideStrings(String content) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inDollarQuote = false;
        String currentDollarTag = null;
        int lastPos = 0;
        
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            
            // Обработка долларовых кавычек ($body$, $function$, etc.)
            if (!inString && !inDollarQuote && c == '$' && i + 1 < content.length()) {
                int end = content.indexOf('$', i + 1);
                if (end > i + 1) {
                    currentDollarTag = content.substring(i, end + 1);
                    inDollarQuote = true;
                    // Добавляем текст до долларовой кавычки
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
            
            // Обработка обычных кавычек
            if (!inDollarQuote && c == '\'' && (i == 0 || content.charAt(i - 1) != '\\')) {
                if (!inString) {
                    // Начало строки - обрабатываем текст до нее
                    String beforeString = content.substring(lastPos, i);
                    result.append(processContent(beforeString));
                    lastPos = i;
                    inString = true;
                } else {
                    // Конец строки
                    result.append(content, lastPos, i + 1);
                    lastPos = i + 1;
                    inString = false;
                }
            }
        }
        
        // Обрабатываем остаток текста
        if (lastPos < content.length()) {
            String remaining = content.substring(lastPos);
            if (!inString && !inDollarQuote) {
                result.append(processContent(remaining));
            } else {
                result.append(remaining);
            }
        }
        
        return result.toString();
    }
    
    /**
     * Обработка SQL кода (вне строковых литералов)
     */
    private String processContent(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Замена вызовов процедур/функций с параметрами
        result = processProcedureCallWithSchema(result);
        result = processProcedureCallWithoutSchema(result);
        
        // Замена таблиц
        result = processTableWithSchema(result);
        result = processTableWithoutSchema(result);
        
        return result;
    }
    
    /**
     * Обработка вызовов процедур/функций с явной схемой
     */
    private String processProcedureCallWithSchema(String content) {
        // Паттерн для поиска: schema.name@dblink(...);
        // Важно: не начинается с кавычки или буквы/цифры
        Pattern pattern = Pattern.compile(
            "(?<![a-zA-Z0-9_'])\\b([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*)*)\\s*@\\s*([a-zA-Z0-9_.]+)\\s*\\(([^)]*)\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String fullName = matcher.group(1);
            String dblink = matcher.group(2);
            String params = matcher.group(3);
            
            // Разбираем полное имя
            String[] parts = fullName.split("\\.");
            String functionName = parts[parts.length - 1];
            String packageName = parts.length > 1 ? parts[parts.length - 2] : "";
            
            String remoteCall;
            if (packageName.isEmpty()) {
                remoteCall = String.format("SELECT %s(%s)", functionName, params);
            } else {
                remoteCall = String.format("SELECT %s.%s(%s)", packageName, functionName, params);
            }
            
            String safeDblink = dblink.replace('.', '_');
            
            String replacement = String.format(
                "SELECT * FROM dblink('%s_server', '%s') AS t(result TEXT);",
                safeDblink,
                remoteCall.replace("'", "''")
            );
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Обработка вызовов процедур/функций без схемы
     */
    private String processProcedureCallWithoutSchema(String content) {
        String searchPath = extractSearchPath(content);
        String firstSchema = searchPath.isEmpty() ? "public" : searchPath.split(",")[0].trim();
        
        Pattern pattern = Pattern.compile(
            "(?<![a-zA-Z0-9_'.])\\b([a-zA-Z_][a-zA-Z0-9_$]*)\\s*@\\s*([a-zA-Z0-9_.]+)\\s*\\(([^)]*)\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String functionName = matcher.group(1);
            String dblink = matcher.group(2);
            String params = matcher.group(3);
            
            // Пропускаем, если это часть schema.function@dblink
            int start = matcher.start();
            if (start > 0 && content.substring(Math.max(0, start - 50), start).matches(".*[a-zA-Z0-9_]\\.[a-zA-Z0-9_$]+\\s*$")) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            
            String safeDblink = dblink.replace('.', '_');
            String remoteCall = String.format("SELECT %s(%s)", functionName, params);
            String replacement = String.format(
                "SELECT * FROM dblink('%s_server', '%s') AS t(result TEXT);",
                safeDblink,
                remoteCall.replace("'", "''")
            );
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Замена таблиц с явной схемой
     */
    private String processTableWithSchema(String content) {
        Pattern pattern = Pattern.compile(
            "(?<![a-zA-Z0-9_'])\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*([a-zA-Z_][a-zA-Z0-9_$]*)\\s*@\\s*([a-zA-Z0-9_.]+)(?![\\s]*\\()",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String schema = matcher.group(1);
            String table = matcher.group(2);
            String dblink = matcher.group(3);
            
            String safeDblink = dblink.replace('.', '_');
            String replacement = String.format("%s_%s.%s", safeDblink, schema, table);
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Замена таблиц без схемы
     */
    private String processTableWithoutSchema(String content) {
        String searchPath = extractSearchPath(content);
        String firstSchema = searchPath.isEmpty() ? "public" : searchPath.split(",")[0].trim();
        
        Pattern pattern = Pattern.compile(
            "(?<![a-zA-Z0-9_'.@])\\b([a-zA-Z_][a-zA-Z0-9_$]*)\\s*@\\s*([a-zA-Z0-9_.]+)(?![\\s]*\\()",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String table = matcher.group(1);
            String dblink = matcher.group(2);
            
            String safeDblink = dblink.replace('.', '_');
            String replacement = String.format("%s_%s.%s", safeDblink, firstSchema, table);
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String extractSearchPath(String content) {
        Pattern pattern = Pattern.compile(
            "SET\\s+search_path\\s*=\\s*([^;]+);",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
}