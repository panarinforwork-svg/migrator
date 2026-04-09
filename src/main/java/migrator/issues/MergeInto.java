package migrator.issues;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import com.tshprecher.postgres.antlr4.PostgreSQLLexer;
import com.tshprecher.postgres.antlr4.PostgreSQLParser;
import com.tshprecher.postgres.antlr4.PostgreSQLParserBaseListener;


public class MergeInto implements Issue {

    @Override
    public String correct(String content) {
        content = moveWhere(content);
        
        // Выводим дерево разбора
        return content;
    }
    
    private String moveWhere(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Паттерн для поиска when ... then ... where конструкций
        // Ищем от "when" до "then", потом любое содержимое, потом "where" до конца клаузулы
        Pattern pattern = Pattern.compile(
            "(?i)(when\\s+(?:not\\s+)?matched\\s+then\\s+.*?)\\s+where\\s+([^;]+?)(?=\\s+when\\s+(?:not\\s+)?matched|;|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String beforeWhere = matcher.group(1);
            String condition = matcher.group(2).trim();
            
            // Преобразуем "when matched then ... where X" в "when matched and X then ..."
            // При этом сохраняем все операции (update, delete, insert) внутри then
            String replacement = beforeWhere.replaceFirst(
                "(?i)(when\\s+(?:not\\s+)?matched)\\s+then",
                "$1 and " + condition + " then"
            );
            
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        String result2 = sb.toString();
        
        // Второй проход: удаляем оставшиеся where которые могли быть после insert/update/delete
        // Но только если они уже не были обработаны
        Pattern cleanupPattern = Pattern.compile(
            "(?i)(when\\s+(?:not\\s+)?matched\\s+and\\s+[^\\s]+.*?then\\s+.*?)\\s+where\\s+[^;]+(?=\\s+when|;|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        
        Matcher cleanupMatcher = cleanupPattern.matcher(result2);
        StringBuffer sb2 = new StringBuffer();
        
        while (cleanupMatcher.find()) {
            String withoutWhere = cleanupMatcher.group(1);
            cleanupMatcher.appendReplacement(sb2, Matcher.quoteReplacement(withoutWhere));
        }
        cleanupMatcher.appendTail(sb2);
        
        return sb2.toString();
    }
}