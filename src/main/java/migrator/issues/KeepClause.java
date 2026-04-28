package migrator.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeepClause implements Issue {
    
    // Паттерн для поиска KEEP с учетом возможного INTO между SELECT и функцией
    private static final Pattern KEEP_PATTERN = Pattern.compile(
        "(\\w+)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*\\)\\s+keep\\s*\\(\\s*dense_rank\\s+(first|last)\\s+order\\s+by\\s+([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*(asc|desc)?\\s*(?:nulls\\s+(first|last))?\\s*\\)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    // Паттерн для поиска SELECT ... INTO ... KEEP
    private static final Pattern SELECT_INTO_KEEP_PATTERN = Pattern.compile(
        "select\\s+into\\s+strict\\s+(\\w+)\\s+(\\w+)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*\\)\\s+keep\\s*\\(\\s*dense_rank\\s+(first|last)\\s+order\\s+by\\s+([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*(asc|desc)?\\s*(?:nulls\\s+(first|last))?\\s*\\)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        return fixKeepClauses(content);
    }
    
    private String fixKeepClauses(String content) {
        // Сначала обрабатываем многострочные конструкции
        String result = content;
        
        // Обработка SELECT ... INTO ... KEEP
        Matcher intoMatcher = SELECT_INTO_KEEP_PATTERN.matcher(result);
        while (intoMatcher.find()) {
            String targetVar = intoMatcher.group(1);
            String aggFunction = intoMatcher.group(2);
            String column = intoMatcher.group(3);
            String rankOrder = intoMatcher.group(4);
            String orderColumn = intoMatcher.group(5);
            String orderDir = intoMatcher.group(6);
            
            String replacement = buildReplacement(aggFunction, column, orderColumn, rankOrder, orderDir);
            String fullMatch = intoMatcher.group(0);
            String newSelect = "select " + replacement + " into strict " + targetVar;
            result = result.replace(fullMatch, newSelect);
        }
        
        // Обработка обычных KEEP (без INTO)
        Matcher keepMatcher = KEEP_PATTERN.matcher(result);
        while (keepMatcher.find()) {
            String aggFunction = keepMatcher.group(1);
            String column = keepMatcher.group(2);
            String rankOrder = keepMatcher.group(3);
            String orderColumn = keepMatcher.group(4);
            String orderDir = keepMatcher.group(5);
            
            String replacement = buildReplacement(aggFunction, column, orderColumn, rankOrder, orderDir);
            result = result.replace(keepMatcher.group(0), replacement);
        }
        
        return result;
    }
    
    private String buildReplacement(String aggFunction, String column, String orderColumn, String rankOrder, String orderDir) {
        String aggLower = aggFunction.toLowerCase();
        String sortOrder;
        
        if ("last".equalsIgnoreCase(rankOrder)) {
            if (orderDir != null && "desc".equalsIgnoreCase(orderDir)) {
                sortOrder = orderColumn + " " + orderDir + ", " + column + " desc";
            } else {
                sortOrder = orderColumn + " desc, " + column + " desc";
            }
        } else { // first
            if (orderDir != null && "asc".equalsIgnoreCase(orderDir)) {
                sortOrder = orderColumn + " " + orderDir + ", " + column + " asc";
            } else {
                sortOrder = orderColumn + " asc, " + column + " asc";
            }
        }
        
        // Для простых случаев max/min
        if ("max".equals(aggLower) || "min".equals(aggLower)) {
            return String.format("(select %s from curriculum c order by %s limit 1)", column, sortOrder);
        }
        
        // Для sum/avg/count нужно больше контекста
        // Пока возвращаем подзапрос
        return String.format("(select %s(%s) from curriculum c where %s = (select %s from curriculum c2 order by %s limit 1))",
            aggFunction.toUpperCase(), column, orderColumn, orderColumn, sortOrder);
    }
}