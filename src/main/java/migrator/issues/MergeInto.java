package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

public class MergeInto implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // 1. Обрабатываем WHEN NOT MATCHED с WHERE
        result = processWhenNotMatched(result);
        
        // 2. Переносим WHERE из UPDATE в WHEN MATCHED AND
        result = moveUpdateConditionToWhenMatched(result);
        
        // 3. Переносим WHERE из DELETE в WHEN MATCHED AND
        result = moveDeleteConditionToWhenMatched(result);
        
        // 4. Обрабатываем WHEN MATCHED с несколькими операторами
        result = processWhenMatched(result);
        
        // 5. Разделяем на отдельные MERGE если есть несколько WHEN MATCHED
        result = splitMergeByConditions(result);
        
        return result;
    }
    
    private String processWhenNotMatched(String content) {
        Pattern pattern = Pattern.compile(
            "(?i)(when\\s+not\\s+matched\\s+then\\s+insert\\s*\\([^)]+\\)\\s+values\\s*\\([^)]+\\))\\s+where\\s+([^;]+)",
            Pattern.DOTALL
        );
        
        String result = content;
        Matcher matcher = pattern.matcher(result);
        
        while (matcher.find()) {
            String insertPart = matcher.group(1);
            String condition = matcher.group(2).trim();
            
            String replacement = insertPart.replaceFirst(
                "(?i)when\\s+not\\s+matched\\s+then",
                "when not matched and " + condition + " then"
            );
            
            result = result.replace(matcher.group(0), replacement);
        }
        
        return result;
    }
    
    private String moveUpdateConditionToWhenMatched(String content) {
        // Переносим WHERE из UPDATE в WHEN MATCHED AND
        Pattern pattern = Pattern.compile(
            "(?i)(when\\s+matched)\\s+then\\s+update\\s+set\\s+([^;]+?)\\s+where\\s+([^;]+)(?=\\s+when|$)",
            Pattern.DOTALL
        );
        
        String result = content;
        Matcher matcher = pattern.matcher(result);
        
        while (matcher.find()) {
            String whenMatched = matcher.group(1);
            String updateSet = matcher.group(2).trim();
            String condition = matcher.group(3).trim();
            
            String replacement = String.format(
                "%s and %s then\n      update set %s",
                whenMatched,
                condition,
                updateSet
            );
            
            result = result.replace(matcher.group(0), replacement);
        }
        
        return result;
    }
    
    private String moveDeleteConditionToWhenMatched(String content) {
        // Переносим WHERE из DELETE в WHEN MATCHED AND
        Pattern pattern = Pattern.compile(
            "(?i)(when\\s+matched)\\s+then\\s+delete\\s+where\\s+([^;]+)",
            Pattern.DOTALL
        );
        
        String result = content;
        Matcher matcher = pattern.matcher(result);
        
        while (matcher.find()) {
            String whenMatched = matcher.group(1);
            String condition = matcher.group(2).trim();
            
            String replacement = String.format(
                "%s and %s then\n      delete",
                whenMatched,
                condition
            );
            
            result = result.replace(matcher.group(0), replacement);
        }
        
        return result;
    }
    
    private String processWhenMatched(String content) {
        String result = content;
        
        Pattern pattern = Pattern.compile(
            "(?i)(when\\s+matched\\s+then)\\s*(.*?)(?=\\s+when\\s+(?:not\\s+)?matched|$)",
            Pattern.DOTALL
        );
        
        Matcher matcher = pattern.matcher(result);
        
        while (matcher.find()) {
            String whenMatchedHeader = matcher.group(1);
            String operations = matcher.group(2);
            
            String[] statements = splitOperations(operations);
            
            if (statements.length > 1) {
                StringBuilder replacement = new StringBuilder();
                
                for (int i = 0; i < statements.length; i++) {
                    String stmt = statements[i].trim();
                    if (!stmt.isEmpty()) {
                        if (i > 0) {
                            replacement.append("\n    ");
                        }
                        replacement.append(whenMatchedHeader).append("\n      ").append(stmt);
                    }
                }
                
                result = result.replace(matcher.group(0), replacement.toString());
            }
        }
        
        return result;
    }
    
    private String splitMergeByConditions(String content) {
        // Ищем MERGE блоки и заменяем их, сохраняя остальной контент
        Pattern mergePattern = Pattern.compile(
            "(?i)(merge\\s+into\\s+\\w+\\s+\\w+\\s+using\\s*\\([^)]+\\)\\s+\\w+\\s+on\\s*\\([^)]+\\))\\s*((?:when\\s+(?:not\\s+)?matched[^;]+)+)\\s*;",
            Pattern.DOTALL
        );
        
        StringBuffer result = new StringBuffer();
        Matcher mergeMatcher = mergePattern.matcher(content);
        
        while (mergeMatcher.find()) {
            String mergeHeader = mergeMatcher.group(1);
            String mergeBody = mergeMatcher.group(2);
            
            // Ищем все WHEN MATCHED блоки
            List<String> matchedBlocks = extractMatchedBlocks(mergeBody);
            
            if (matchedBlocks.size() > 1) {
                // Создаем отдельные MERGE для каждого WHEN MATCHED
                StringBuilder replacement = new StringBuilder();
                
                for (int i = 0; i < matchedBlocks.size(); i++) {
                    String block = matchedBlocks.get(i);
                    String whenNotMatched = extractWhenNotMatched(mergeBody);
                    
                    replacement.append(mergeHeader).append("\n");
                    replacement.append(block);
                    
                    // Добавляем WHEN NOT MATCHED только в первый MERGE
                    if (i == 0 && whenNotMatched != null && !whenNotMatched.isEmpty()) {
                        replacement.append("\n").append(whenNotMatched);
                    }
                    
                    replacement.append(";");
                    
                    // Добавляем разделитель между MERGE (кроме последнего)
                    if (i < matchedBlocks.size() - 1) {
                        replacement.append("\n\n");
                    }
                }
                
                mergeMatcher.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
            } else {
                // Оставляем как есть
                mergeMatcher.appendReplacement(result, Matcher.quoteReplacement(mergeMatcher.group(0)));
            }
        }
        mergeMatcher.appendTail(result);
        
        return result.toString();
    }
    
    private List<String> extractMatchedBlocks(String mergeBody) {
        List<String> blocks = new ArrayList<>();
        
        // Ищем все WHEN MATCHED блоки
        Pattern pattern = Pattern.compile(
            "(?i)(when\\s+matched[^;]+?)(?=\\s+when\\s+(?:not\\s+)?matched|$)",
            Pattern.DOTALL
        );
        
        Matcher matcher = pattern.matcher(mergeBody);
        while (matcher.find()) {
            String block = matcher.group(1).trim();
            if (!block.isEmpty()) {
                blocks.add(block);
            }
        }
        
        return blocks;
    }
    
    private String extractWhenNotMatched(String mergeBody) {
        Pattern pattern = Pattern.compile(
            "(?i)(when\\s+not\\s+matched[^;]+)",
            Pattern.DOTALL
        );
        
        Matcher matcher = pattern.matcher(mergeBody);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        return null;
    }
    
    private String[] splitOperations(String operations) {
        List<String> ops = new ArrayList<>();
        
        Pattern updatePattern = Pattern.compile(
            "(?i)update\\s+set[^;]+?(?=delete|when|$)",
            Pattern.DOTALL
        );
        
        Pattern deletePattern = Pattern.compile(
            "(?i)delete\\s+where[^;]+?(?=update|when|$)",
            Pattern.DOTALL
        );
        
        Matcher updateMatcher = updatePattern.matcher(operations);
        while (updateMatcher.find()) {
            ops.add(updateMatcher.group().trim());
        }
        
        Matcher deleteMatcher = deletePattern.matcher(operations);
        while (deleteMatcher.find()) {
            ops.add(deleteMatcher.group().trim());
        }
        
        return ops.toArray(new String[0]);
    }
}