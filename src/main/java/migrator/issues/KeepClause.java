package migrator.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeepClause implements Issue {
    
    // Паттерн для KEEP в SELECT (без INTO) - в виде "max(...) keep(...) AS alias"
    private static final Pattern KEEP_IN_SELECT_PATTERN = Pattern.compile(
        "(max|min)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*\\)\\s+keep\\s*\\(\\s*dense_rank\\s+(first|last)\\s+order\\s+by\\s+(.+?)\\s*\\)\\s+AS\\s+(\\w+)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    // Паттерн для KEEP без AS
    private static final Pattern KEEP_PLAIN_PATTERN = Pattern.compile(
        "(max|min)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*\\)\\s+keep\\s*\\(\\s*dense_rank\\s+(first|last)\\s+order\\s+by\\s+(.+?)\\s*\\)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    // Паттерн для SELECT ... INTO ... KEEP
    private static final Pattern KEEP_SELECT_INTO_PATTERN = Pattern.compile(
        "select\\s+(max|min)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*\\)\\s+keep\\s*\\(\\s*dense_rank\\s+(first|last)\\s+order\\s+by\\s+(.+?)\\s*\\)\\s+into\\s+strict\\s+(\\w+)\\s+from\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // 1. Обработка KEEP в SELECT с AS
        result = fixKeepInSelect(result);
        
        // 2. Обработка KEEP в SELECT без AS
        result = fixKeepPlain(result);
        
        // 3. Обработка SELECT INTO KEEP
        result = fixKeepSelectInto(result);
        
        return result;
    }
    
    private String fixKeepInSelect(String content) {
        Matcher matcher = KEEP_IN_SELECT_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String aggFunction = matcher.group(1);
            String column = matcher.group(2);
            String rankOrder = matcher.group(3);
            String orderByExpr = matcher.group(4);
            String alias = matcher.group(5);
            
            String direction = "last".equalsIgnoreCase(rankOrder) ? "DESC" : "ASC";
            String cleanColumn = column.contains(".") ? column.substring(column.lastIndexOf(".") + 1) : column;
            
            // Пытаемся определить таблицу из колонки (префикс)
            String tableAlias = column.contains(".") ? column.substring(0, column.indexOf(".")) : "t";
            
            String replacement = String.format(
                "(SELECT %s FROM %s ORDER BY (%s) %s, %s %s LIMIT 1) AS %s",
                cleanColumn, tableAlias, orderByExpr, direction, cleanColumn, direction, alias
            );
            
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
    
    private String fixKeepPlain(String content) {
        Matcher matcher = KEEP_PLAIN_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String aggFunction = matcher.group(1);
            String column = matcher.group(2);
            String rankOrder = matcher.group(3);
            String orderByExpr = matcher.group(4);
            
            String direction = "last".equalsIgnoreCase(rankOrder) ? "DESC" : "ASC";
            String cleanColumn = column.contains(".") ? column.substring(column.lastIndexOf(".") + 1) : column;
            String tableAlias = column.contains(".") ? column.substring(0, column.indexOf(".")) : "t";
            
            String replacement = String.format(
                "(SELECT %s FROM %s ORDER BY (%s) %s, %s %s LIMIT 1)",
                cleanColumn, tableAlias, orderByExpr, direction, cleanColumn, direction
            );
            
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
    
    private String fixKeepSelectInto(String content) {
        Matcher matcher = KEEP_SELECT_INTO_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String aggFunction = matcher.group(1);
            String column = matcher.group(2);
            String rankOrder = matcher.group(3);
            String orderByExpr = matcher.group(4);
            String targetVar = matcher.group(5);
            
            String direction = "last".equalsIgnoreCase(rankOrder) ? "DESC" : "ASC";
            String cleanColumn = column.contains(".") ? column.substring(column.lastIndexOf(".") + 1) : column;
            String tableAlias = column.contains(".") ? column.substring(0, column.indexOf(".")) : "t";
            
            String replacement = String.format(
                "SELECT (SELECT %s FROM %s ORDER BY (%s) %s, %s %s LIMIT 1) INTO STRICT %s FROM",
                cleanColumn, tableAlias, orderByExpr, direction, cleanColumn, direction, targetVar
            );
            
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
}