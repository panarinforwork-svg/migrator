package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FixRecursiveCTE implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Преобразуем RECURSIVE CTE с двумя селектами в GENERATE_SERIES
        result = transformRecursiveCTE(result);
        
        return result;
    }
    
    private String transformRecursiveCTE(String content) {
        // Ищем блок WITH RECURSIVE ... SELECT * FROM cte
        Pattern pattern = Pattern.compile(
            "(?i)WITH\\s+RECURSIVE\\s+\\w+\\s+AS\\s*\\(\\s*SELECT\\s+(.*?)\\s+level\\s*<\\s*(\\d+)\\s+UNION\\s+ALL\\s+SELECT\\s+.*?\\s+level\\s*<\\s*\\d+\\s+JOIN\\s+\\w+\\s+\\w+\\s+ON\\s*\\(\\)\\s*\\)\\s*SELECT\\s+\\*\\s+FROM\\s+\\w+(.*)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String selectColumns = matcher.group(1);
            String maxLevel = matcher.group(2);
            String orderByClause = matcher.group(3);
            
            // Очищаем колонки от "level < N" в конце
            selectColumns = cleanSelectColumns(selectColumns);
            
            // level < N → GENERATE_SERIES(1, N-1)
            int endAt = Integer.parseInt(maxLevel) - 1;
            
            // Формируем новый SELECT
            String replacement = String.format(
                "SELECT %s\n    FROM GENERATE_SERIES(1, %d) AS level%s",
                selectColumns,
                endAt,
                orderByClause != null ? orderByClause : ""
            );
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String cleanSelectColumns(String columns) {
        // Убираем "level < N" в конце каждой строки
        String cleaned = columns.replaceAll("(?i)\\s+level\\s*<\\s*\\d+\\s*", "");
        
        // Убираем лишние пробелы
        cleaned = cleaned.replaceAll("\\s+", " ");
        
        // Убираем пробелы перед запятыми
        cleaned = cleaned.replaceAll("\\s+,", ",");
        
        return cleaned.trim();
    }
}