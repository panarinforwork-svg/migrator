package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.regex.*;

public class RemoveInsertAlias implements Issue {

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        // Ищем INSERT INTO schema.table alias (пробелы) (SELECT|VALUES|()
        // Используем \b для границ слова
        Pattern pattern = Pattern.compile(
            "(?i)(INSERT\\s+INTO\\s+)(\\w+(?:\\.\\w+)?)\\s+(\\w+)\\s*((?i:SELECT|VALUES|\\())",
            Pattern.DOTALL
        );
        
        StringBuffer sb = new StringBuffer();
        Matcher m = pattern.matcher(content);
        
        while (m.find()) {
            String prefix = m.group(1);      // "INSERT INTO "
            String table = m.group(2);       // schema.table
            String alias = m.group(3);       // алиас
            String keyword = m.group(4);     // SELECT, VALUES, или '('
            
            // Просто удаляем алиас, оставляя пробел после table
            String replacement = prefix + table + " " + keyword;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        
        return sb.toString();
    }
}