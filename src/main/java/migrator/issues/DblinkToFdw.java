package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DblinkToFdw implements Issue {

    private static final boolean LOGGING_ENABLED = true; // отключить после проверки

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
                    result.append(processDblinkConstructs(beforeString));
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
                result.append(processDblinkConstructs(remaining));
            } else {
                result.append(remaining);
            }
        }

        return result.toString();
    }

    private String processDblinkConstructs(String sql) {
        if (sql == null || sql.isBlank()) return sql;
        if (LOGGING_ENABLED) {
            System.out.println("[DblinkToFdw] processDblinkConstructs called, length=" + sql.length());
        }
        String result = replaceFunctionCalls(sql);
        result = replaceTableReferences(result);
        return result;
    }

    /**
     * Замена вызовов функций/процедур: имя@dblink(параметры);
     * Исправлено: теперь копируется весь текст до вызова.
     */
    private String replaceFunctionCalls(String sql) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = sql.length();
        boolean found = false;

        while (i < len) {
            int atPos = sql.indexOf('@', i);
            if (atPos == -1) {
                result.append(sql.substring(i));
                break;
            }
            // Находим начало имени перед '@'
            int startName = atPos - 1;
            while (startName >= i && (Character.isLetterOrDigit(sql.charAt(startName)) ||
                    sql.charAt(startName) == '_' || sql.charAt(startName) == '.' || sql.charAt(startName) == '$')) {
                startName--;
            }
            startName++;
            String leftPart = sql.substring(startName, atPos).trim();
            if (leftPart.isEmpty() || !leftPart.matches("[a-zA-Z_][a-zA-Z0-9_$.]*")) {
                result.append(sql.substring(i, atPos + 1));
                i = atPos + 1;
                continue;
            }
            // После '@' ищем имя dblink
            int afterAt = atPos + 1;
            while (afterAt < len && Character.isWhitespace(sql.charAt(afterAt))) afterAt++;
            int dblinkStart = afterAt;
            while (afterAt < len && (Character.isLetterOrDigit(sql.charAt(afterAt)) ||
                    sql.charAt(afterAt) == '_' || sql.charAt(afterAt) == '.')) afterAt++;
            String dblinkName = sql.substring(dblinkStart, afterAt);
            if (dblinkName.isEmpty()) {
                result.append(sql.substring(i, afterAt));
                i = afterAt;
                continue;
            }
            // Пропускаем пробелы до '('
            while (afterAt < len && Character.isWhitespace(sql.charAt(afterAt))) afterAt++;
            if (afterAt >= len || sql.charAt(afterAt) != '(') {
                result.append(sql.substring(i, afterAt));
                i = afterAt;
                continue;
            }
            // Ищем соответствующую закрывающую скобку с учётом вложенности
            int openParen = afterAt;
            int depth = 1;
            int pos = openParen + 1;
            while (pos < len && depth > 0) {
                char ch = sql.charAt(pos);
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                if (depth == 0) break;
                pos++;
            }
            if (depth != 0) {
                result.append(sql.substring(i, openParen + 1));
                i = openParen + 1;
                continue;
            }
            int closeParen = pos;
            // Проверяем ';' после скобки
            int afterParen = closeParen + 1;
            while (afterParen < len && Character.isWhitespace(sql.charAt(afterParen))) afterParen++;
            if (afterParen >= len || sql.charAt(afterParen) != ';') {
                result.append(sql.substring(i, afterParen));
                i = afterParen;
                continue;
            }

            // Всё нашли
            String params = sql.substring(openParen + 1, closeParen);
            int endOfCall = afterParen + 1;

            if (LOGGING_ENABLED) {
                System.out.println("[DblinkToFdw] FUNCTION CALL found:");
                System.out.println("   leftPart = " + leftPart);
                System.out.println("   dblink   = " + dblinkName);
                System.out.println("   params   = " + params);
            }

            // Копируем текст от i до startName (то, что было до вызова)
            result.append(sql.substring(i, startName));

            String[] parts = leftPart.split("\\.");
            String functionName = parts[parts.length - 1];
            String packageName = parts.length > 1 ? parts[parts.length - 2] : null;

            // НЕ добавляем суффикс 7
            String remoteCall;
            if (packageName != null) {
                remoteCall = String.format("SELECT %s.%s(%s)", packageName, functionName, params);
            } else {
                remoteCall = String.format("SELECT %s(%s)", functionName, params);
            }
            String safeDblink = dblinkName.replace('.', '_');
            String replacement = String.format(
                "SELECT * FROM dblink('%s_server', '%s') AS t(result TEXT);",
                safeDblink,
                remoteCall.replace("'", "''")
            );

            if (LOGGING_ENABLED) {
                System.out.println("   заменяем на: " + replacement);
            }

            result.append(replacement);
            i = endOfCall;
            found = true;
        }

        if (!found && LOGGING_ENABLED) {
            System.out.println("[DblinkToFdw] replaceFunctionCalls: ничего не найдено.");
        }
        return result.toString();
    }

    private String replaceTableReferences(String sql) {
        Pattern pattern = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*)?)\\s*@\\s*([a-zA-Z0-9_.]+)(?!\\s*\\()",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
        );
        StringBuffer sb = new StringBuffer();
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            String objectName = m.group(1);
            String dblinkName = m.group(2);
            int start = m.start();
            boolean insideDblink = false;
            int lastDblink = sql.lastIndexOf("dblink(", start);
            if (lastDblink != -1) {
                int openPos = lastDblink + 7;
                int depth = 1;
                for (int i = openPos; i < start; i++) {
                    if (sql.charAt(i) == '(') depth++;
                    else if (sql.charAt(i) == ')') depth--;
                    if (depth == 0) {
                        insideDblink = false;
                        break;
                    }
                }
                if (depth > 0) insideDblink = true;
            }
            if (insideDblink) {
                if (LOGGING_ENABLED) {
                    System.out.println("[DblinkToFdw] TABLE REFERENCE пропущена (внутри dblink): " + objectName + "@" + dblinkName);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            if (LOGGING_ENABLED) {
                System.out.println("[DblinkToFdw] TABLE REFERENCE найдена: " + objectName + "@" + dblinkName);
            }
            String safeDblink = dblinkName.replace('.', '_');
            String replacement;
            if (objectName.contains(".")) {
                String[] parts = objectName.split("\\.");
                String schema = parts[0];
                String table = parts[1];
                replacement = String.format("%s_%s.%s", safeDblink, schema, table);
            } else {
                String firstSchema = extractFirstSchema(sql);
                replacement = String.format("%s_%s.%s", safeDblink, firstSchema, objectName);
            }
            if (LOGGING_ENABLED) {
                System.out.println("   заменяем на: " + replacement);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String extractFirstSchema(String sql) {
        Pattern p = Pattern.compile("SET\\s+search_path\\s*=\\s*([^;]+);", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        if (m.find()) {
            String path = m.group(1);
            String[] schemas = path.split(",");
            if (schemas.length > 0) return schemas[0].trim();
        }
        return "public";
    }
}