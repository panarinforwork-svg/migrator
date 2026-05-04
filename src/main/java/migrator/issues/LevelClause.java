package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LevelClause implements Issue {
    
    private static final Pattern LEVEL_PATTERN = Pattern.compile(
        "\\bLEVEL\\b",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern CONNECT_BY_PATTERN = Pattern.compile(
        "CONNECT\\s+BY\\s+LEVEL\\s*<=\\s*(\\d+)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern START_WITH_CONNECT_BY = Pattern.compile(
        "START\\s+WITH\\s+.*?CONNECT\\s+BY\\s+LEVEL\\s*<=\\s*(\\d+)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        return fixLevelClauses(content);
    }
    
    private String fixLevelClauses(String content) {
        String result = content;
        
        // Замена CONNECT BY LEVEL <= N на рекурсивный CTE
        Matcher connectMatcher = CONNECT_BY_PATTERN.matcher(result);
        while (connectMatcher.find()) {
            int maxLevel = Integer.parseInt(connectMatcher.group(1));
            String cte = String.format(
                "WITH RECURSIVE cte AS (\n  SELECT 1 AS level\n  UNION ALL\n  SELECT level + 1 FROM cte WHERE level + 1 <= %d\n)\nSELECT level FROM cte",
                maxLevel
            );
            result = result.replace(connectMatcher.group(0), cte);
        }
        
        // Замена одинокого LEVEL (без CONNECT BY)
        Matcher levelMatcher = LEVEL_PATTERN.matcher(result);
        result = levelMatcher.replaceAll("cte.level");
        
        return result;
    }
}
