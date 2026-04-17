package migrator.issues;

import java.sql.*;
import java.util.*;
import java.util.regex.*;

public class ProcedureCall implements Issue {
    
    private static final String DB_URL = "jdbc:oracle:thin:@localhost:1521/ORCLPDB1";
    private static final String DB_USER = "CMOP";
    private static final String DB_PASSWORD = "CMOP";
    
    private final Set<String> procedureCache = new HashSet<>();
    private boolean debug = false; // Отключаем отладку
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        // Используем простой и надёжный подход с Matcher.replaceAll
        return fixProcedureCalls(content);
    }
    
    private String fixProcedureCalls(String content) {
        // Разбиваем на строки для безопасной обработки
        String[] lines = content.split("\n", -1);
        List<String> resultLines = new ArrayList<>();
        
        boolean insideBlockComment = false;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            
            // Проверяем, находимся ли внутри блочного комментария
            if (insideBlockComment) {
                resultLines.add(line);
                // Проверяем, не закрывается ли комментарий в этой строке
                if (line.contains("*/")) {
                    insideBlockComment = false;
                }
                continue;
            }
            
            // Проверяем начало блочного комментария
            int blockCommentStart = line.indexOf("/*");
            if (blockCommentStart != -1) {
                // Проверяем, не закрывается ли комментарий в этой же строке
                int blockCommentEnd = line.indexOf("*/", blockCommentStart + 2);
                if (blockCommentEnd == -1) {
                    // Комментарий не закрыт в этой строке
                    insideBlockComment = true;
                    resultLines.add(line);
                    continue;
                }
            }
            
            // Пропускаем однострочные комментарии
            if (trimmed.startsWith("--")) {
                resultLines.add(line);
                continue;
            }
            
            // Проверяем, является ли строка началом вызова процедуры
            if (isProcedureCallStart(line)) {
                // Проверяем, что вызов не внутри комментария
                if (isInsideComment(line, blockCommentStart)) {
                    resultLines.add(line);
                    continue;
                }
                
                // Проверяем, не является ли это функцией в выражении
                if (isInExpression(line)) {
                    resultLines.add(line);
                    continue;
                }
                
                // Собираем полный вызов (может быть на нескольких строках)
                StringBuilder fullCall = new StringBuilder(line);
                int j = i + 1;
                
                // Если вызов не завершён в этой строке, собираем следующие строки
                if (!isCallComplete(line)) {
                    while (j < lines.length) {
                        String nextLine = lines[j];
                        fullCall.append("\n").append(nextLine);
                        
                        if (isCallComplete(nextLine) || nextLine.trim().endsWith(");")) {
                            j++;
                            break;
                        }
                        j++;
                    }
                }
                
                // Добавляем CALL к полному вызову
                String callText = fullCall.toString();
                String fixedCall = addCallToProcedureCall(callText);
                
                if (fixedCall != null && !fixedCall.equals(callText)) {
                    // Добавляем исправленный вызов
                    String[] callLines = fixedCall.split("\n", -1);
                    for (String callLine : callLines) {
                        resultLines.add(callLine);
                    }
                    i = j - 1; // Пропускаем обработанные строки
                    continue;
                }
            }
            
            resultLines.add(line);
        }
        
        return String.join("\n", resultLines);
    }

    private boolean isInsideComment(String line, int blockCommentStart) {
        // Проверяем, есть ли закрывающий комментарий перед вызовом
        int callStart = line.indexOf("(");
        if (callStart == -1) return false;
        
        // Проверяем, нет ли /* перед вызовом
        int lastBlockStart = line.lastIndexOf("/*", callStart);
        if (lastBlockStart != -1) {
            // Проверяем, не закрыт ли этот комментарий перед вызовом
            int lastBlockEnd = line.lastIndexOf("*/", callStart);
            if (lastBlockEnd == -1 || lastBlockEnd < lastBlockStart) {
                return true; // Мы внутри блочного комментария
            }
        }
        
        return false;
    }
    
    private boolean isProcedureCallStart(String line) {
        String trimmed = line.trim();
        
        // Ищем паттерн: word.word( или word.word.word(
        return trimmed.matches("^[a-zA-Z_][a-zA-Z0-9_$#]*\\.[a-zA-Z_][a-zA-Z0-9_$#]*(\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?\\s*\\(.*");
    }
    
    private boolean isCallComplete(String line) {
        String trimmed = line.trim();
        
        // Вызов завершён, если есть ); в конце (игнорируя комментарии)
        int semicolonPos = trimmed.indexOf(';');
        if (semicolonPos > 0) {
            // Проверяем, что ; не в комментарии
            String beforeSemicolon = trimmed.substring(0, semicolonPos);
            int commentPos = beforeSemicolon.indexOf("--");
            if (commentPos == -1) {
                return true;
            }
        }
        
        return trimmed.endsWith(");");
    }
    
    private boolean isInExpression(String line) {
        String trimmed = line.trim();
        
        // Если строка начинается с := или содержит SELECT/INTO/RETURN/VALUES
        return trimmed.matches("^.*(?i)(:=|SELECT\\s+|INTO\\s+|RETURN\\s+|VALUES\\s*\\()\\s*.*") ||
               trimmed.startsWith(":=");
    }
    
    private String addCallToProcedureCall(String callText) {
        // Извлекаем имя процедуры
        Pattern namePattern = Pattern.compile("^\\s*([a-zA-Z_][a-zA-Z0-9_$#.]*)\\s*\\(");
        Matcher nameMatcher = namePattern.matcher(callText);
        
        if (nameMatcher.find()) {
            String procedureName = nameMatcher.group(1);
            
            // Проверяем, что это процедура
            if (isProcedure(procedureName)) {
                // Находим отступ
                String indent = "";
                int firstNonSpace = 0;
                while (firstNonSpace < callText.length() && callText.charAt(firstNonSpace) == ' ') {
                    indent += " ";
                    firstNonSpace++;
                }
                
                // Добавляем CALL после отступа
                return indent + "CALL " + callText.substring(firstNonSpace);
            }
        }
        
        return callText;
    }
    
    private boolean isProcedure(String name) {
        String upperName = name.toUpperCase();
        
        if (procedureCache.contains(upperName)) {
            return true;
        }
        
        // Эвристика для известных пакетов
        if (name.toLowerCase().startsWith("dbms_") || 
            name.toLowerCase().startsWith("utl_") ||
            name.toLowerCase().contains("_pkg.")) {
            procedureCache.add(upperName);
            return true;
        }
        
        // Для всего остального - если содержит точку, считаем процедурой
        if (name.contains(".")) {
            procedureCache.add(upperName);
            return true;
        }
        
        return false;
    }
}