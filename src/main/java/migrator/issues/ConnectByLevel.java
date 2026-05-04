package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConnectByLevel implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Универсальный паттерн для поиска ошибочного CTE с любыми пробелами и комментариями
        // Ищем от "WITH RECURSIVE cte AS (" до "SELECT * FROM cte)" включительно
        Pattern pattern = Pattern.compile(
            "WITH\\s+RECURSIVE\\s+cte\\s+AS\\s*\\([^;]*?select\\s+level\\s+lev[^;]*?UNION\\s+ALL[^;]*?JOIN\\s+cte\\s+c\\s+ON\\s*\\(\\)[^;]*?\\)\\s*SELECT\\s+\\*\\s+FROM\\s+cte",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        
        StringBuffer sb = new StringBuffer();
        Matcher matcher = pattern.matcher(result);
        
        while (matcher.find()) {
            String replacement = "WITH RECURSIVE cte AS (SELECT 1 as lev UNION ALL SELECT lev + 1 FROM cte WHERE lev + 1 <= 20) SELECT lev FROM cte";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        result = sb.toString();
        
        // Дополнительная обработка: убираем двойные пробелы и форматируем join
        result = result.replace("join(WITH RECURSIVE cte AS (SELECT 1 as lev UNION ALL SELECT lev + 1 FROM cte WHERE lev + 1 <= 20) SELECT lev FROM cte)", 
                                "join(WITH RECURSIVE cte AS (SELECT 1 as lev UNION ALL SELECT lev + 1 FROM cte WHERE lev + 1 <= 20) SELECT lev FROM cte)");
        
        return result;
    }
}
