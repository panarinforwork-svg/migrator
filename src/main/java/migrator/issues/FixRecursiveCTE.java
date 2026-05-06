package migrator.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.ArrayList;
import java.util.List;

public class FixRecursiveCTE implements Issue {

    @Override
    public String correct(String content) {
        // Ищем "WITH RECURSIVE cte AS ("
        int startIdx = content.indexOf("WITH RECURSIVE");
        if (startIdx == -1) return content;
        
        // Находим закрывающую скобку после "SELECT * FROM cte"
        int asPos = content.indexOf("AS (", startIdx);
        if (asPos == -1) return content;
        
        int bodyStart = asPos + 3;
        // Считаем баланс скобок, чтобы найти конец тела
        int balance = 1;
        int bodyEnd = -1;
        for (int i = bodyStart; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '(') balance++;
            else if (ch == ')') {
                balance--;
                if (balance == 0) {
                    bodyEnd = i;
                    break;
                }
            }
        }
        if (bodyEnd == -1) return content;
        
        // Ищем "SELECT * FROM cte" после тела
        int afterBody = bodyEnd + 1;
        while (afterBody < content.length() && Character.isWhitespace(content.charAt(afterBody))) afterBody++;
        // Пропускаем возможную закрывающую скобку от "AS (...)"
        if (afterBody < content.length() && content.charAt(afterBody) == ')') afterBody++;
        while (afterBody < content.length() && Character.isWhitespace(content.charAt(afterBody))) afterBody++;
        
        if (!content.startsWith("SELECT * FROM", afterBody)) {
            return content;
        }
        int selectEnd = afterBody + "SELECT * FROM".length();
        while (selectEnd < content.length() && Character.isWhitespace(content.charAt(selectEnd))) selectEnd++;
        if (!content.startsWith("cte", selectEnd)) {
            return content;
        }
        
        // Находим закрывающую скобку всего подзапроса: ищем '(' перед WITH
        int openParen = -1;
        for (int i = startIdx - 1; i >= 0; i--) {
            if (content.charAt(i) == '(') {
                openParen = i;
                break;
            }
            if (!Character.isWhitespace(content.charAt(i))) break;
        }
        if (openParen == -1) return content;
        
        int closeParen = -1;
        int parenBalance = 1;
        for (int i = openParen + 1; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '(') parenBalance++;
            else if (ch == ')') {
                parenBalance--;
                if (parenBalance == 0) {
                    closeParen = i;
                    break;
                }
            }
        }
        if (closeParen == -1) return content;
        
        // Теперь извлекаем тело CTE и заменяем
        String cteBody = content.substring(bodyStart, bodyEnd);
        String replacement = transformCte(cteBody);
        if (replacement == null) return content;
        
        // Заменяем от openParen до closeParen+1
        String result = content.substring(0, openParen) + replacement + content.substring(closeParen + 1);
        return result;
    }
    
    private String transformCte(String body) {
        // Находим первую часть до UNION ALL
        int unionPos = body.toUpperCase().indexOf("UNION ALL");
        if (unionPos == -1) return null;
        String initial = body.substring(0, unionPos).trim();
        
        // Ищем "level < N"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("level\\s*<\\s*(\\d+)");
        var m = p.matcher(initial);
        if (!m.find()) return null;
        int maxLevel = Integer.parseInt(m.group(1));
        int end = maxLevel - 1;
        
        // Извлекаем часть SELECT
        String selectPart = initial.substring(0, m.start()).trim();
        if (selectPart.toUpperCase().startsWith("SELECT")) {
            selectPart = selectPart.substring(6).trim();
        }
        
        // Разбираем колонки
        List<String[]> columns = new ArrayList<>();
        for (String col : selectPart.split("\\s*,\\s*")) {
            col = col.trim();
            if (col.isEmpty()) continue;
            String[] parts = col.split("\\s+");
            String expr = parts[0];
            String alias = parts.length > 1 ? parts[parts.length - 1] : expr;
            expr = expr.replaceAll("\\blevel\\b", "gs");
            columns.add(new String[]{expr, alias});
        }
        
        if (columns.isEmpty()) return null;
        StringBuilder selectList = new StringBuilder();
        for (String[] col : columns) {
            if (selectList.length() > 0) selectList.append(", ");
            selectList.append(col[0]).append(" AS ").append(col[1]);
        }
        
        return String.format("(SELECT %s FROM generate_series(1, %d) AS gs)", selectList, end);
    }
}