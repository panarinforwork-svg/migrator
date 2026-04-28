package migrator.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SubstrNamedParams implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Обрабатываем все вызовы substr с именованными параметрами
        result = fixSubstrWithNamedParams(result);
        
        // Удаляем оставшиеся касты ::integer, которые могли появиться после исправления
        result = result.replaceAll("\\)\\s*::\\s*integer", ")");
        result = result.replaceAll("\\(\\s*([^)]+)\\s*\\)\\s*::\\s*integer", "($1)");
        
        return result;
    }
    
    private String fixSubstrWithNamedParams(String content) {
        // Ищем substr( ... ) где внутри есть => (именованные параметры)
        // Паттерн ищет substr, затем открывающую скобку, затем любое содержимое (с учетом вложенных скобок)
        // и закрывающую скобку. Используем DOTALL для многострочности.
        Pattern pattern = Pattern.compile(
            "\\bsubstr\\s*\\(((?:[^()]|\\([^()]*\\))*+)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        
        StringBuffer sb = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            String args = matcher.group(1);
            
            // Проверяем, есть ли в аргументах именованные параметры
            if (args.contains("=>")) {
                String converted = convertNamedSubstr(args);
                String replacement = "substr(" + converted + ")";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(fullMatch));
            }
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
    
    /**
     * Преобразует строку аргументов с именованными параметрами в позиционные.
     * Пример входа: "lob_loc => v_clob, (offset => v_offset)::integer, (amount => least(v_amount, v_buflen - v_len))::integer"
     * Выход: "v_clob, v_offset, least(v_amount, v_buflen - v_len)"
     */
    private String convertNamedSubstr(String args) {
        // Разбиваем аргументы на части, учитывая, что внутри могут быть скобки и запятые
        List<String> argParts = splitArguments(args);
        
        String lobLoc = null;
        String offset = null;
        String amount = null;
        
        for (String part : argParts) {
            part = part.trim();
            if (part.toLowerCase().startsWith("lob_loc =>")) {
                lobLoc = extractValueFromAssignment(part);
            } else if (part.toLowerCase().startsWith("offset =>")) {
                offset = extractValueFromAssignment(part);
            } else if (part.toLowerCase().startsWith("amount =>")) {
                amount = extractValueFromAssignment(part);
            } else {
                // Если нет явного имени, возможно, это позиционный параметр (но по логике задачи их не должно быть)
                // На всякий случай сохраняем как lobLoc, если он еще не задан
                if (lobLoc == null) lobLoc = cleanValue(part);
                else if (offset == null) offset = cleanValue(part);
                else if (amount == null) amount = cleanValue(part);
            }
        }
        
        // Формируем результат
        StringBuilder result = new StringBuilder();
        if (lobLoc != null) {
            result.append(lobLoc);
        } else {
            result.append("null");
        }
        
        if (offset != null) {
            result.append(", ").append(offset);
        } else {
            result.append(", 1");
        }
        
        if (amount != null) {
            result.append(", ").append(amount);
        }
        
        return result.toString();
    }
    
    /**
     * Извлекает значение из части вида "param => value"
     * Удаляет касты ::integer и лишние скобки.
     */
    private String extractValueFromAssignment(String part) {
        // Ищем после =>
        Pattern p = Pattern.compile("=>\\s*(.+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(part);
        if (m.find()) {
            String value = m.group(1).trim();
            return cleanValue(value);
        }
        return null;
    }
    
    /**
     * Очищает значение от внешних скобок и кастов ::integer
     */
    private String cleanValue(String value) {
        if (value == null) return null;
        String result = value.trim();
        
        // Удаляем каст ::integer в конце
        result = result.replaceAll("::integer\\s*$", "");
        
        // Удаляем внешние скобки, если они обрамляют всё выражение
        while (result.startsWith("(") && result.endsWith(")")) {
            // Проверяем, что скобки не являются частью вызова функции (например, least(...))
            // Просто снимаем, если они парные и не нарушают структуру
            result = result.substring(1, result.length() - 1).trim();
        }
        
        return result;
    }
    
    /**
     * Разбивает строку аргументов на отдельные параметры, учитывая вложенные скобки.
     * Пример: "a => b, c => d, e => f(g, h)" -> ["a => b", "c => d", "e => f(g, h)"]
     */
    private List<String> splitArguments(String args) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenLevel = 0;
        boolean inString = false;
        char stringChar = '\'';
        
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            
            // Обработка строковых литералов
            if ((c == '\'' || c == '"') && !inString) {
                inString = true;
                stringChar = c;
                current.append(c);
                continue;
            } else if (inString && c == stringChar) {
                inString = false;
                current.append(c);
                continue;
            }
            
            if (!inString) {
                if (c == '(') {
                    parenLevel++;
                } else if (c == ')') {
                    parenLevel--;
                } else if (c == ',' && parenLevel == 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }
}