package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InsertRecordIssue implements Issue {

    // Паттерн для INSERT ... VALUES record_variable
    // Группы:
    // 1 - всё до INSERT (можно не использовать)
    // 2 - INSERT INTO table_name (optional column list)
    // 3 - VALUES (опционально, может отсутствовать)
    // 4 - имя переменной-записи
    // 5 - RETURNING ... (опционально)
    private static final Pattern INSERT_VALUES_RECORD = Pattern.compile(
            "(?i)(\\s*insert\\s+into\\s+([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)(?:\\s*\\([^)]*\\))?)\\s+(values?\\s+)?([a-zA-Z_][a-zA-Z0-9_$#]*)(\\s+returning\\s+.*?)?(?=(;|\\s+into|$))",
            Pattern.DOTALL
    );

    // Дополнительный паттерн на случай, если после record_variable сразу идёт RETURNING (без пробелов)
    private static final Pattern INSERT_RECORD_RETURNING = Pattern.compile(
            "(?i)(\\s*insert\\s+into\\s+([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)(?:\\s*\\([^)]*\\))?)\\s+([a-zA-Z_][a-zA-Z0-9_$#]*)\\s+(returning\\s+.*?)(?=(;|$))",
            Pattern.DOTALL
    );

    // Простой вариант: INSERT INTO table record_var (без VALUES и без RETURNING)
    private static final Pattern INSERT_PLAIN_RECORD = Pattern.compile(
            "(?i)(\\s*insert\\s+into\\s+([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)(?:\\s*\\([^)]*\\))?)\\s+([a-zA-Z_][a-zA-Z0-9_$#]*)\\s*(;|$)",
            Pattern.DOTALL
    );

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;

        String result = content;

        // 1. Обработка INSERT ... VALUES record_var ... [RETURNING ...]
        result = fixInsertValuesRecord(result);

        // 2. Обработка INSERT ... record_var RETURNING ...
        result = fixInsertRecordReturning(result);

        // 3. Обработка INSERT ... record_var (без VALUES и без RETURNING)
        result = fixInsertPlainRecord(result);

        return result;
    }

    /**
     * Обрабатывает случаи: INSERT INTO table VALUES record_var [RETURNING ...]
     */
    private String fixInsertValuesRecord(String content) {
        Matcher matcher = INSERT_VALUES_RECORD.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String prefix = matcher.group(1);      // "INSERT INTO table"
            String tableName = matcher.group(2);   // имя таблицы (может быть схема)
            String valuesKeyword = matcher.group(3); // "VALUES " или null
            String recordVar = matcher.group(4);    // имя переменной-записи
            String returningPart = matcher.group(5); // " RETURNING ..." или null

            // Формируем корректную вставку
            String replacement = prefix + " SELECT " + recordVar + ".*";
            if (returningPart != null && !returningPart.isEmpty()) {
                replacement += " " + returningPart.trim();
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Обрабатывает случаи: INSERT INTO table record_var RETURNING ...
     */
    private String fixInsertRecordReturning(String content) {
        Matcher matcher = INSERT_RECORD_RETURNING.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String prefix = matcher.group(1);
            String tableName = matcher.group(2);
            String recordVar = matcher.group(3);
            String returningPart = matcher.group(4);

            String replacement = prefix + " SELECT " + recordVar + ".* " + returningPart;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Обрабатывает простые случаи: INSERT INTO table record_var (без RETURNING)
     */
    private String fixInsertPlainRecord(String content) {
        Matcher matcher = INSERT_PLAIN_RECORD.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String prefix = matcher.group(1);
            String tableName = matcher.group(2);
            String recordVar = matcher.group(3);
            String end = matcher.group(4); // ';' или конец строки

            String replacement = prefix + " SELECT " + recordVar + ".* " + end;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
