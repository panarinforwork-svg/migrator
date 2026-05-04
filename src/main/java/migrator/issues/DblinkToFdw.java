package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DblinkToFdw implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Основная замена: schema.table@dblink -> dblink_schema.table
        result = processDblinkToFdwSchema(result);
        
        // Обрабатываем table@dblink (без схемы)
        result = processSimpleDblinkWithContext(result);
        
        return result;
    }
    
    /**
     * Преобразование schema.table@dblink -> dblink_schema.table
     */
    private String processDblinkToFdwSchema(String content) {
        // Паттерн: schema.table@dblink
        Pattern pattern = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*@\\s*([a-zA-Z_][a-zA-Z0-9_]*)",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String schema = matcher.group(1);      // исходная схема (например, cmop, okuser)
            String table = matcher.group(2);       // таблица
            String dblink = matcher.group(3);      // имя dblink (например, dec7)
            
            // Заменяем на dblink_schema.table
            String replacement = String.format("%s_%s.%s", dblink, schema, table);
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Обработка случаев table@dblink (без явной схемы)
     */
    private String processSimpleDblinkWithContext(String content) {
        // Ищем search_path настройки
        String searchPath = extractSearchPath(content);
        
        // Паттерн: table@dblink (без точки)
        Pattern pattern = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*@\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\b",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String table = matcher.group(1);
            String dblink = matcher.group(2);
            
            String replacement;
            if (!searchPath.isEmpty()) {
                // Используем первую схему из search_path
                String firstSchema = searchPath.split(",")[0].trim();
                replacement = String.format("%s_%s.%s", dblink, firstSchema, table);
            } else {
                // Если нет search_path, используем только dblink
                replacement = String.format("%s.%s", dblink, table);
            }
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Извлечение search_path из PL/pgSQL кода
     */
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