package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AddCallToProcedures implements Issue {

    @Override
    public String correct(String content) {
        content = addCallToProcedureCalls(content);
        return content;
    }
    
    private String addCallToProcedureCalls(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Паттерн для поиска вызовов процедур в PL/pgSQL блоке
        // Ищем схемы.процедура(параметры) или просто процедура(параметры)
        // Исключаем случаи, когда уже есть CALL, SELECT, PERFORM, RETURN, INTO и т.д.
        Pattern pattern = Pattern.compile(
            "(?<!\\bCALL\\s+)(?<!\\bSELECT\\s+)(?<!\\bPERFORM\\s+)(?<!\\bRETURN\\s+)(?<![\\w.])(\\w+(?:\\.\\w+)?)\\s*\\(([^;]*?)\\)(?=\\s*;|\\s*$|\\s+INTO\\s+)(?![\\s\\w]*\\()",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String procedureName = matcher.group(1);
            String parameters = matcher.group(2);
            
            // Проверяем, что это не встроенная функция и не ключевое слово
            if (isUserProcedure(procedureName, matcher.group(0), result, matcher.start())) {
                String replacement = "CALL " + procedureName + "(" + parameters + ")";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
    
    private boolean isUserProcedure(String procedureName, String fullMatch, String content, int position) {
        // Проверяем, не находится ли вызов внутри комментария
        if (isInsideComment(content, position)) {
            return false;
        }
        
        // Список встроенных функций и ключевых слов PostgreSQL, которые не требуют CALL
        String[] reservedFunctions = {
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "VALUES",
            "EXECUTE", "PREPARE", "DEALLOCATE", "DECLARE", "OPEN", "FETCH",
            "CLOSE", "MOVE", "SCROLL", "CURSOR", "RETURN", "PERFORM",
            "CASE", "WHEN", "THEN", "ELSE", "END", "IF", "ELSEIF", "LOOP",
            "WHILE", "FOR", "FOREACH", "EXIT", "CONTINUE", "BEGIN", "END",
            "RAISE", "ASSERT", "GET", "SET", "RESET", "SHOW", "COPY",
            "GRANT", "REVOKE", "COMMENT", "REINDEX", "VACUUM", "ANALYZE"
        };
        
        String upperName = procedureName.toUpperCase();
        for (String reserved : reservedFunctions) {
            if (upperName.equals(reserved)) {
                return false;
            }
        }
        
        // Проверяем, что это не вызов функции с INTO (SELECT func() INTO var)
        if (fullMatch.matches("(?i).*\\s+INTO\\s+.*")) {
            return false;
        }
        
        return true;
    }
    
    private boolean isInsideComment(String content, int position) {
        // Проверяем, находится ли позиция внутри однострочного комментария
        int lastLineComment = content.lastIndexOf("--", position);
        if (lastLineComment != -1) {
            int endOfLine = content.indexOf("\n", lastLineComment);
            if (endOfLine == -1) endOfLine = content.length();
            if (position > lastLineComment && position < endOfLine) {
                return true;
            }
        }
        
        // Проверяем, находится ли позиция внутри многострочного комментария
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