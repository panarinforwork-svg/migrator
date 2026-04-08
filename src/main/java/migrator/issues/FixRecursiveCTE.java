package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FixRecursiveCTE implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Преобразуем неправильный RECURSIVE CTE в GENERATE_SERIES
        result = fixRecursiveCTEToGenerateSeries(result);
        
        // Дополнительная очистка от артефактов
        result = cleanUpRemainingArtifacts(result);
        
        return result;
    }
    
    private String fixRecursiveCTEToGenerateSeries(String content) {
        // Ищем конструкцию WITH RECURSIVE cte AS ( ... ) SELECT * FROM cte
        Pattern pattern = Pattern.compile(
            "(?i)WITH\\s+RECURSIVE\\s+(\\w+)\\s+AS\\s*\\(\\s*SELECT\\s+level\\s+(\\w+)\\s+level\\s*<\\s*(\\d+)\\s+UNION\\s+ALL\\s+SELECT\\s+level\\s+\\2\\s+level\\s*<\\s*\\d+\\s+JOIN\\s+\\1\\s+\\w+\\s+ON\\s*\\(\\)\\s*\\)\\s*SELECT\\s+\\*\\s+FROM\\s+\\1",
            Pattern.DOTALL
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String columnName = matcher.group(2);
            String maxValue = matcher.group(3);
            
            // Заменяем на простой GENERATE_SERIES
            String replacement = String.format(
                "SELECT GENERATE_SERIES(1, %s) AS %s",
                maxValue,
                columnName
            );
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String cleanUpRemainingArtifacts(String content) {
        String result = content;
        
        // Удаляем лишние скобки вокруг подзапроса
        result = result.replaceAll("\\(\\s*SELECT\\s+GENERATE_SERIES", "(SELECT GENERATE_SERIES");
        
        // Исправляем "from (SELECT GENERATE_SERIES...) n" -> "from (SELECT GENERATE_SERIES...) n"
        result = result.replaceAll("from\\s+\\(\\s*SELECT\\s+GENERATE_SERIES", "from (SELECT GENERATE_SERIES");
        
        return result;
    }
}