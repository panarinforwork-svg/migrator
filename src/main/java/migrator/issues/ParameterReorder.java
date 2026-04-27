package migrator.issues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ParameterReorder implements Issue {
    private static final Map<String, MethodSignature> changedSignatures = new LinkedHashMap<>();

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        String result = processCreateFunction(content);
        result = processCreateProcedure(result);
        return result;
    }

    private String processCreateFunction(String sql) {
        Pattern pattern = Pattern.compile(
            "(?i)(CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+(\\w+(?:\\.\\w+)?)\\s*\\()",
            Pattern.DOTALL);
        return processDDL(sql, pattern, true);
    }

    private String processCreateProcedure(String sql) {
        Pattern pattern = Pattern.compile(
            "(?i)(CREATE\\s+OR\\s+REPLACE\\s+PROCEDURE\\s+(\\w+(?:\\.\\w+)?)\\s*\\()",
            Pattern.DOTALL);
        return processDDL(sql, pattern, false);
    }

    private String processDDL(String sql, Pattern startPattern, boolean isFunction) {
        StringBuffer result = new StringBuffer();
        Matcher startMatcher = startPattern.matcher(sql);
        int lastEnd = 0;

        while (startMatcher.find()) {
            result.append(sql, lastEnd, startMatcher.start());
            String prefix = startMatcher.group(1);        // заканчивается на '('
            String fullName = startMatcher.group(2);

            int paramsStart = startMatcher.end() - 1;     // позиция '('
            int paramsEnd = findMatchingParen(sql, paramsStart);
            if (paramsEnd == -1) {
                result.append(prefix + "(");
                lastEnd = startMatcher.end();
                continue;
            }

            String paramsRaw = sql.substring(paramsStart + 1, paramsEnd);
            List<Param> originalParams = parseParameters(paramsRaw);
            List<Param> reorderedParams = reorderParameters(originalParams);
            String newParams = formatParameters(reorderedParams);

            changedSignatures.put(fullName.toLowerCase(),
                new MethodSignature(originalParams, reorderedParams, isFunction));

            int headerEnd = findHeaderEnd(sql, paramsEnd + 1);
            String suffix = sql.substring(paramsEnd, headerEnd); // начинается с ')'

            // Исправлено: не удаляем '('
            String replacement = prefix + newParams + suffix;
            result.append(replacement);
            lastEnd = headerEnd;
        }
        result.append(sql.substring(lastEnd));
        return result.toString();
    }
    
    private boolean isSameOrder(List<Param> original, List<Param> reordered) {
        if (original.size() != reordered.size()) return false;
        for (int i = 0; i < original.size(); i++) {
            if (!original.get(i).name.equalsIgnoreCase(reordered.get(i).name))
                return false;
        }
        return true;
    }

    private String formatParameters(List<Param> params, List<Param> originalParams) {
        // Форматируем, стараясь сохранить переводы строк и комментарии
        List<String> formatted = new ArrayList<>();
        for (Param p : params) {
            String dir = "";
            if (p.direction == Direction.OUT) dir = "OUT ";
            else if (p.direction == Direction.INOUT) dir = "INOUT ";
            String def = p.hasDefault ? " DEFAULT " + p.defaultValue : "";
            String comment = (p.trailingComment != null && !p.trailingComment.isEmpty()) 
                ? " " + p.trailingComment : "";
            formatted.add(dir + p.name + " " + p.type + def + comment);
        }
        // Если в исходном были переводы строк, вставляем их после каждого параметра, кроме последнего
        boolean hasNewlines = originalParams.stream().anyMatch(p -> p.trailingComment != null && p.trailingComment.contains("\n"));
        if (hasNewlines) {
            return formatted.stream().collect(Collectors.joining(",\n"));
        } else {
            return String.join(", ", formatted);
        }
    }

    private int findMatchingParen(String sql, int openPos) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = openPos; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') inSingleQuote = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '"') inDoubleQuote = false;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int findHeaderEnd(String sql, int start) {
        // Ищем "AS" не внутри кавычек, не внутри скобок, после чего идет текст процедуры
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int depthParen = 0;
        for (int i = start; i < sql.length() - 1; i++) {
            char c = sql.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') inSingleQuote = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '"') inDoubleQuote = false;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (c == '(') depthParen++;
            else if (c == ')') depthParen--;
            if (depthParen == 0 && (c == 'A' || c == 'a') && (sql.charAt(i+1) == 'S' || sql.charAt(i+1) == 's')) {
                // Нашли "AS" вне кавычек и скобок
                return i;
            }
        }
        return sql.length();
    }

    private List<Param> reorderParameters(List<Param> original) {
        List<Param> result = new ArrayList<>();
        // 1. IN без DEFAULT
        original.stream()
            .filter(p -> p.direction == Direction.IN && !p.hasDefault)
            .forEach(result::add);
        // 2. OUT (не могут иметь DEFAULT)
        original.stream()
            .filter(p -> p.direction == Direction.OUT)
            .forEach(result::add);
        // 3. INOUT (могут иметь DEFAULT, но идут после OUT)
        original.stream()
            .filter(p -> p.direction == Direction.INOUT)
            .forEach(result::add);
        // 4. IN с DEFAULT
        original.stream()
            .filter(p -> p.direction == Direction.IN && p.hasDefault)
            .forEach(result::add);
        return result;
    }

    private List<Param> parseParameters(String paramsRaw) {
        List<Param> params = new ArrayList<>();
        List<String> tokens = splitParams(paramsRaw);
        for (String token : tokens) {
            Param p = new Param();
            String trimmed = token.trim();
            
            // Сохраняем комментарий
            int commentIndex = findCommentIndex(trimmed);
            String trailingComment = "";
            if (commentIndex > 0) {
                trailingComment = trimmed.substring(commentIndex);
                trimmed = trimmed.substring(0, commentIndex).trim();
            }
            
            // Определение направления
            if (trimmed.toUpperCase().startsWith("OUT ")) {
                p.direction = Direction.OUT;
                trimmed = trimmed.substring(3).trim();
            } else if (trimmed.toUpperCase().startsWith("INOUT ")) {
                p.direction = Direction.INOUT;
                trimmed = trimmed.substring(5).trim();
            } else if (trimmed.toUpperCase().startsWith("IN ")) {
                p.direction = Direction.IN;
                trimmed = trimmed.substring(2).trim();
            } else {
                p.direction = Direction.IN;
            }

            // Ищем DEFAULT
            Pattern defaultPattern = Pattern.compile("(?i)\\s+DEFAULT\\s+(.*)$");
            Matcher dm = defaultPattern.matcher(trimmed);
            if (dm.find()) {
                p.hasDefault = true;
                p.defaultValue = dm.group(1).trim();
                trimmed = trimmed.substring(0, dm.start()).trim();
            } else {
                p.hasDefault = false;
            }

            // Имя и тип
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace > 0) {
                p.name = trimmed.substring(0, lastSpace).trim();
                p.type = trimmed.substring(lastSpace + 1).trim();
            } else {
                p.name = trimmed;
                p.type = "unknown";
            }
            
            // Сохраняем комментарий
            p.trailingComment = trailingComment;
            
            params.add(p);
        }
        return params;
    }

    private int findCommentIndex(String s) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') inSingleQuote = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '"') inDoubleQuote = false;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (c == '-' && s.charAt(i + 1) == '-') {
                return i;
            }
        }
        return -1;
    }

    private List<String> splitParams(String paramsRaw) {
        List<String> result = new ArrayList<>();
        int parenLevel = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        StringBuilder current = new StringBuilder();
        
        for (int i = 0; i < paramsRaw.length(); i++) {
            char c = paramsRaw.charAt(i);
            
            if (inLineComment) {
                current.append(c);
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            
            if (inSingleQuote) {
                current.append(c);
                if (c == '\'') inSingleQuote = false;
                continue;
            }
            if (inDoubleQuote) {
                current.append(c);
                if (c == '"') inDoubleQuote = false;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                current.append(c);
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                current.append(c);
                continue;
            }
            if (c == '-' && i + 1 < paramsRaw.length() && paramsRaw.charAt(i + 1) == '-') {
                inLineComment = true;
                current.append(c);
                continue;
            }
            if (c == '(') {
                parenLevel++;
                current.append(c);
            } else if (c == ')') {
                parenLevel--;
                current.append(c);
            } else if (c == ',' && parenLevel == 0 && !inLineComment) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    private String formatParameters(List<Param> params) {
        return params.stream()
            .map(p -> {
                String dir = "";
                if (p.direction == Direction.OUT) dir = "OUT ";
                else if (p.direction == Direction.INOUT) dir = "INOUT ";
                String def = p.hasDefault ? " DEFAULT " + p.defaultValue : "";
                String comment = (p.trailingComment != null && !p.trailingComment.isEmpty()) 
                    ? " " + p.trailingComment 
                    : "";
                return dir + p.name + " " + p.type + def + comment;
            })
            .collect(Collectors.joining(", "));
    }

    // Добавь поле в класс Param
    static class Param {
        Direction direction = Direction.IN;
        String name;
        String type;
        boolean hasDefault = false;
        String defaultValue;
        String trailingComment = ""; // новое поле
    }

    // Вспомогательные классы
    enum Direction { IN, OUT, INOUT }

    public static class MethodSignature {
        public final List<Param> originalParams;
        public final List<Param> reorderedParams;
        public final boolean isFunction;

        public MethodSignature(List<Param> original, List<Param> reordered, boolean isFunction) {
            this.originalParams = original;
            this.reorderedParams = reordered;
            this.isFunction = isFunction;
        }
    }

    public static Map<String, MethodSignature> getChangedSignatures() {
        return changedSignatures;
    }
}