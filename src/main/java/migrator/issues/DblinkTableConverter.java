package migrator.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DblinkTableConverter implements Issue {

    private static final boolean LOGGING_ENABLED = true;

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        return processOutsideStrings(content);
    }

    private String processOutsideStrings(String content) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inDollarQuote = false;
        String currentDollarTag = null;
        int lastPos = 0;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            // Долларовые кавычки
            if (!inString && !inDollarQuote && c == '$' && i + 1 < content.length()) {
                int end = content.indexOf('$', i + 1);
                if (end > i + 1) {
                    currentDollarTag = content.substring(i, end + 1);
                    inDollarQuote = true;
                    result.append(content, lastPos, i);
                    lastPos = i;
                    continue;
                }
            }
            if (inDollarQuote && i + currentDollarTag.length() - 1 < content.length()) {
                String possibleTag = content.substring(i, i + currentDollarTag.length());
                if (possibleTag.equals(currentDollarTag)) {
                    inDollarQuote = false;
                    currentDollarTag = null;
                }
                continue;
            }
            // Обычные строки
            if (!inDollarQuote && c == '\'' && (i == 0 || content.charAt(i - 1) != '\\')) {
                if (!inString) {
                    String beforeString = content.substring(lastPos, i);
                    result.append(replaceTableReferences(beforeString));
                    lastPos = i;
                    inString = true;
                } else {
                    result.append(content, lastPos, i + 1);
                    lastPos = i + 1;
                    inString = false;
                }
            }
        }
        if (lastPos < content.length()) {
            String remaining = content.substring(lastPos);
            if (!inString && !inDollarQuote) {
                result.append(replaceTableReferences(remaining));
            } else {
                result.append(remaining);
            }
        }
        return result.toString();
    }

    private String protectStringLiterals(String sql, List<String> literals) {
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        int lastPos = 0;
        int start = 0;
        
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                if (!inString) {
                    // начало строки
                    start = i;
                    inString = true;
                } else {
                    // конец строки
                    String literal = sql.substring(start, i + 1);
                    literals.add(literal);
                    sb.append(sql, lastPos, start);
                    sb.append("___STR_LIT_").append(literals.size() - 1).append("___");
                    lastPos = i + 1;
                    inString = false;
                }
            }
        }
        sb.append(sql, lastPos, sql.length());
        return sb.toString();
    }

    private String replaceTableReferences(String sql) {
        // Шаг 0: защищаем строковые литералы
        List<String> stringLiterals = new ArrayList<>();
        String withoutStrings = protectStringLiterals(sql, stringLiterals);
        
        // Шаг 1: временно заменяем уже готовые вызовы dblink(...) на маркеры
        List<String> dblinkCalls = new ArrayList<>();
        String temp = withoutStrings;
        Pattern dblinkPattern = Pattern.compile("dblink\\([^)]*?(?:\\([^)]*\\))?[^)]*\\)", Pattern.DOTALL);
        Matcher m = dblinkPattern.matcher(temp);
        int idx = 0;
        while (m.find()) {
            String call = m.group();
            dblinkCalls.add(call);
            temp = temp.substring(0, m.start()) + "___DBLINK_CALL_" + idx + "___" + temp.substring(m.end());
            m = dblinkPattern.matcher(temp);
            idx++;
        }

        // Шаг 2: ищем таблицы (что-то@dblink) в оставшемся тексте
        Pattern tablePattern = Pattern.compile(
                "\\b([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*)?)\\s*@\\s*([a-zA-Z0-9_.]+)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
        );
        StringBuffer sb = new StringBuffer();
        Matcher tm = tablePattern.matcher(temp);
        while (tm.find()) {
            String objectName = tm.group(1);
            String dblinkName = tm.group(2);
            if (LOGGING_ENABLED) {
                System.out.println("[TABLE] found: " + objectName + "@" + dblinkName);
            }
            String safeDblink = dblinkName.replace('.', '_');
            String replacement;
            if (objectName.contains(".")) {
                String[] parts = objectName.split("\\.");
                String schema = parts[0];
                String table = parts[1];
                replacement = String.format("%s_%s.%s", safeDblink, schema, table);
            } else {
                replacement = String.format("%s.%s", safeDblink, objectName);
            }
            if (LOGGING_ENABLED) {
                System.out.println("  -> replaced with: " + replacement);
            }
            tm.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        tm.appendTail(sb);
        String withMarkers = sb.toString();

        // Шаг 3: восстановить вызовы dblink
        for (int i = 0; i < dblinkCalls.size(); i++) {
            withMarkers = withMarkers.replace("___DBLINK_CALL_" + i + "___", dblinkCalls.get(i));
        }
        
        // Шаг 4: восстановить строковые литералы
        for (int i = 0; i < stringLiterals.size(); i++) {
            withMarkers = withMarkers.replace("___STR_LIT_" + i + "___", Matcher.quoteReplacement(stringLiterals.get(i)));
        }
        
        return withMarkers;
    }
}