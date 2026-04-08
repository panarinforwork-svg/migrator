package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DbmsLoad implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Обрабатываем dbms_lob.read
        result = processDbmsLobRead(result);
        
        // Обрабатываем substr
        result = processSubstr(result);
        
        // Обрабатываем lpad
        result = processLpad(result);
        
        // Обрабатываем DBMS_LOB.CREATETEMPORARY
        result = processDbmsLobCreateTemporary(result);
        
        return result;
    }
    
    private String processDbmsLobCreateTemporary(String content) {
        // Поддерживает разные форматы:
        // DBMS_LOB.CREATETEMPORARY(v_xml, TRUE);
        // DBMS_LOB.CREATETEMPORARY(v_xml, TRUE, DBMS_LOB.CALL);
        // DBMS_LOB.CREATETEMPORARY(lob_loc => v_xml, cache => TRUE);
        
        Pattern pattern = Pattern.compile(
            "DBMS_LOB\\.CREATETEMPORARY\\s*\\([^)]*\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String match = matcher.group(0);
            
            // Извлекаем имя переменной (первый параметр)
            Pattern varPattern = Pattern.compile(
                "(?i)(?:lob_loc\\s*=>\\s*)?(\\w+)(?:\\s*[,)])",
                Pattern.DOTALL
            );
            Matcher varMatcher = varPattern.matcher(match);
            
            String variable = null;
            if (varMatcher.find()) {
                variable = varMatcher.group(1);
            }
            
            if (variable != null) {
                String replacement = variable + " := '';";
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            } else {
                // Если не удалось извлечь переменную, комментируем строку
                matcher.appendReplacement(result, Matcher.quoteReplacement("-- " + match));
            }
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String processDbmsLobRead(String content) {
        // Ищем dbms_lob.read( ... ) с учетом вложенных скобок
        Pattern pattern = Pattern.compile(
            "dbms_lob\\.read\\s*\\(([^()]*+(?:\\([^()]*+\\)[^()]*+)*+)\\)",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String args = matcher.group(1);
            String[] parts = splitArgs(args);
            
            if (parts.length >= 4) {
                String blob = parts[0].trim();
                String amount = parts[1].trim();
                String offset = parts[2].trim();
                String buffer = parts[3].trim();
                
                String replacement = String.format(
                    "%s := substr(%s, %s, (%s)::integer);\n EXIT WHEN length(%s) = 0",
                    buffer, blob, offset, amount, buffer
                );
                
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(result, matcher.group(0));
            }
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String processSubstr(String content) {
        // Ищем substr( ... ) с учетом вложенных скобок
        Pattern pattern = Pattern.compile(
            "substr\\s*\\(([^()]*+(?:\\([^()]*+\\)[^()]*+)*+)\\)",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String args = matcher.group(1);
            String[] parts = splitArgs(args);
            
            StringBuilder converted = new StringBuilder("substr(");
            
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) converted.append(", ");
                String arg = parts[i].trim();
                
                // Добавляем ::integer к числовым аргументам (2-й и 3-й)
                if (i >= 1 && i <= 2) {
                    arg = addIntegerCast(arg);
                }
                converted.append(arg);
            }
            converted.append(")");
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(converted.toString()));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String processLpad(String content) {
        // Ищем lpad( ... ) с учетом вложенных скобок
        Pattern pattern = Pattern.compile(
            "lpad\\s*\\(([^()]*+(?:\\([^()]*+\\)[^()]*+)*+)\\)",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String args = matcher.group(1);
            String[] parts = splitArgs(args);
            
            StringBuilder converted = new StringBuilder("lpad(");
            
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) converted.append(", ");
                String arg = parts[i].trim();
                
                // Добавляем ::integer ко 2-му аргументу (длина)
                if (i == 1) {
                    arg = addIntegerCast(arg);
                }
                converted.append(arg);
            }
            converted.append(")");
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(converted.toString()));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String addIntegerCast(String arg) {
        if (arg == null || arg.isEmpty()) return arg;
        
        String trimmed = arg.trim();
        
        // Пропускаем, если уже есть каст
        if (trimmed.contains("::integer")) {
            return arg;
        }
        
        // Пропускаем строки в кавычках
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return arg;
        }
        
        // Пропускаем NULL
        if (trimmed.equalsIgnoreCase("null")) {
            return arg;
        }
        
        // Добавляем каст
        return "(" + arg + ")::integer";
    }
    
    private String[] splitArgs(String args) {
        // Разделяем аргументы с учетом вложенных скобок
        java.util.List<String> result = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenLevel = 0;
        
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            
            if (c == '(') {
                parenLevel++;
                current.append(c);
            } else if (c == ')') {
                parenLevel--;
                current.append(c);
            } else if (c == ',' && parenLevel == 0) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            result.add(current.toString());
        }
        
        return result.toArray(new String[0]);
    }
}