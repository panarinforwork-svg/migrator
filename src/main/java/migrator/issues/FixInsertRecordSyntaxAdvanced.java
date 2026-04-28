package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FixInsertRecordSyntaxAdvanced implements Issue {

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        return fixAllInsertSyntaxVariants(content);
    }
    
    private String fixAllInsertSyntaxVariants(String content) {
        String result = content;
        
        // Паттерн для разных вариантов форматирования:
        // 1. insert into table (i.*);
        // 2. insert into table ( i.* );
        // 3. insert into schema.table (rec.*);
        // 4. С переносами строк
        Pattern pattern = Pattern.compile(
            "(?i)(\\bINSERT\\s+INTO\\s+\\w+(?:\\.\\w+)?\\s*)\\(\\s*(\\w+)\\.\\*\\s*\\)\\s*([;]?)",
            Pattern.DOTALL | Pattern.MULTILINE
        );
        
        StringBuffer sb = new StringBuffer();
        Matcher matcher = pattern.matcher(result);
        
        while (matcher.find()) {
            if (isInsideComment(result, matcher.start())) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            
            String insertPrefix = matcher.group(1);
            String recordName = matcher.group(2);
            String semicolon = matcher.group(3);
            
            // Формируем правильный INSERT
            String replacement = insertPrefix + "VALUES (" + recordName + ".*)" + semicolon;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
    
    private boolean isInsideComment(String content, int position) {
        // Проверка однострочного комментария
        int lastLineComment = content.lastIndexOf("--", position);
        if (lastLineComment != -1) {
            int endOfLine = content.indexOf("\n", lastLineComment);
            if (endOfLine == -1) endOfLine = content.length();
            if (position > lastLineComment && position < endOfLine) {
                return true;
            }
        }
        
        // Проверка многострочного комментария
        int lastBlockCommentStart = content.lastIndexOf("/*", position);
        if (lastBlockCommentStart != -1) {
            int blockCommentEnd = content.indexOf("*/", lastBlockCommentStart);
            if (blockCommentEnd == -1) blockCommentEnd = content.length();
            if (position > lastBlockCommentStart && position < blockCommentEnd) {
                return true;
            }
        }
        
        return false;
    }
}
