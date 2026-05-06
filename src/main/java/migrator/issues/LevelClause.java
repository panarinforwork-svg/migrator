package migrator.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LevelClause implements Issue {

    @Override
    public String correct(String content) {
        String result = content;
        
        // Паттерн для поиска рекурсивных CTE с level
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "WITH\\s+RECURSIVE\\s+cte\\s+AS\\s*\\(\\s*" +
            "SELECT\\s+(.+?)\\s+" +
            "level\\s*(<=?)\\s*(\\d+)\\s*" +
            "UNION\\s+ALL\\s+" +
            "SELECT\\s+.+?\\s+" +
            "level\\s*\\2\\s*\\3\\s*" +
            "JOIN\\s+cte\\s+c\\s+ON\\s*\\(\\)\\s*" +
            "\\)\\s*SELECT\\s+(.+?)\\s+FROM\\s+cte",
            java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        
        java.util.regex.Matcher m = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        
        while (m.find()) {
            String columns = m.group(1).trim();
            String operator = m.group(2);
            String maxLevel = m.group(3);
            String selectList = m.group(4).trim();
            
            // Заменяем level на gs в колонках
            String finalColumns = columns.replaceAll("\\blevel\\b", "gs");
            
            // Формируем generate_series
            String seriesExpr;
            if (operator.equals("<")) {
                seriesExpr = String.format("generate_series(1, %s - 1)", maxLevel);
            } else {
                seriesExpr = String.format("generate_series(1, %s)", maxLevel);
            }
            
            String replacement = String.format("(SELECT %s FROM %s AS gs)", finalColumns, seriesExpr);
            
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        result = sb.toString();
        
        // Дополнительно: ищем CTE которые используются как подзапросы в JOIN
        java.util.regex.Pattern joinPattern = java.util.regex.Pattern.compile(
            "join\\s*\\(\\s*WITH\\s+RECURSIVE\\s+cte\\s+AS\\s*\\(\\s*" +
            "SELECT\\s+(.+?)\\s+" +
            "level\\s*(<=?)\\s*(\\d+)\\s*" +
            "UNION\\s+ALL\\s+" +
            "SELECT\\s+.+?\\s+" +
            "level\\s*\\2\\s*\\3\\s*" +
            "JOIN\\s+cte\\s+c\\s+ON\\s*\\(\\)\\s*" +
            "\\)\\s*SELECT\\s+(.+?)\\s+FROM\\s+cte\\s*\\)",
            java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        
        m = joinPattern.matcher(result);
        sb = new StringBuffer();
        
        while (m.find()) {
            String columns = m.group(1).trim();
            String operator = m.group(2);
            String maxLevel = m.group(3);
            String selectList = m.group(4).trim();
            
            String finalColumns = columns.replaceAll("\\blevel\\b", "gs");
            
            String seriesExpr;
            if (operator.equals("<")) {
                seriesExpr = String.format("generate_series(1, %s - 1)", maxLevel);
            } else {
                seriesExpr = String.format("generate_series(1, %s)", maxLevel);
            }
            
            String replacement = String.format("(SELECT %s FROM %s AS gs)", finalColumns, seriesExpr);
            
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        result = sb.toString();
        
        return result;
    }
}
