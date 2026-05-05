package migrator.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeepClause implements Issue {

    private static final Pattern KEEP_IN_SELECT_PATTERN = Pattern.compile(
        "(max|min)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*\\)\\s+keep\\s*\\(\\s*dense_rank\\s+(first|last)\\s+order\\s+by\\s+(.+?)\\s*\\)\\s+AS\\s+(\\w+)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern KEEP_PLAIN_PATTERN = Pattern.compile(
        "(max|min)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*\\)\\s+keep\\s*\\(\\s*dense_rank\\s+(first|last)\\s+order\\s+by\\s+(.+?)\\s*\\)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern KEEP_SELECT_INTO_PATTERN = Pattern.compile(
        "select\\s+(max|min)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_$#]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$#]*)?)\\s*\\)\\s+keep\\s*\\(\\s*dense_rank\\s+(first|last)\\s+order\\s+by\\s+(.+?)\\s*\\)\\s+into\\s+strict\\s+(\\w+)\\s+from\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;

        String result = content;
        result = fixKeepInSelect(result);
        result = fixKeepPlain(result);
        result = fixKeepSelectInto(result);
        return result;
    }

    /**
     * Преобразует выражение ORDER BY из Oracle KEEP в корректный ORDER BY для подзапроса.
     * Удаляет из orderByExpr оригинальные ASC/DESC и добавляет нужное направление.
     *
     * @param rawOrderBy   выражение из паттерна (например "ai.imp_date asc")
     * @param keepDirection "first" или "last"
     * @return строка для ORDER BY, например "ai.imp_date DESC"
     */
    private String normalizeOrderBy(String rawOrderBy, String keepDirection) {
        String trimmed = rawOrderBy.trim();
        // Удаляем завершающие ASC или DESC (регистронезависимо, с возможными пробелами)
        trimmed = trimmed.replaceAll("(?i)\\s+(asc|desc)\\s*$", "");
        String sortDir = "last".equalsIgnoreCase(keepDirection) ? "DESC" : "ASC";
        return trimmed + " " + sortDir;
    }

    private String fixKeepInSelect(String content) {
        Matcher matcher = KEEP_IN_SELECT_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String aggFunction = matcher.group(1);
            String column = matcher.group(2);
            String rankOrder = matcher.group(3);
            String rawOrderBy = matcher.group(4);
            String alias = matcher.group(5);

            String orderByClause = normalizeOrderBy(rawOrderBy, rankOrder);
            String cleanColumn = column.contains(".") ? column.substring(column.lastIndexOf(".") + 1) : column;
            String tableAlias = column.contains(".") ? column.substring(0, column.indexOf(".")) : "t";

            String replacement = String.format(
                "(SELECT %s FROM %s ORDER BY %s, %s %s LIMIT 1) AS %s",
                cleanColumn, tableAlias, orderByClause, cleanColumn, 
                orderByClause.contains("DESC") ? "DESC" : "ASC", alias
            );
            // Второй столбец в ORDER BY нужен для детерминированности, можно упростить:
            // orderByClause уже содержит направление, используем его повторно.
            // Но для простоты оставим как было с небольшим улучшением:
            replacement = String.format(
                "(SELECT %s FROM %s ORDER BY %s LIMIT 1) AS %s",
                cleanColumn, tableAlias, orderByClause, alias
            );

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String fixKeepPlain(String content) {
        Matcher matcher = KEEP_PLAIN_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String aggFunction = matcher.group(1);
            String column = matcher.group(2);
            String rankOrder = matcher.group(3);
            String rawOrderBy = matcher.group(4);

            String orderByClause = normalizeOrderBy(rawOrderBy, rankOrder);
            String cleanColumn = column.contains(".") ? column.substring(column.lastIndexOf(".") + 1) : column;
            String tableAlias = column.contains(".") ? column.substring(0, column.indexOf(".")) : "t";

            String replacement = String.format(
                "(SELECT %s FROM %s ORDER BY %s LIMIT 1)",
                cleanColumn, tableAlias, orderByClause
            );

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String fixKeepSelectInto(String content) {
        Matcher matcher = KEEP_SELECT_INTO_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String aggFunction = matcher.group(1);
            String column = matcher.group(2);
            String rankOrder = matcher.group(3);
            String rawOrderBy = matcher.group(4);
            String targetVar = matcher.group(5);

            String orderByClause = normalizeOrderBy(rawOrderBy, rankOrder);
            String cleanColumn = column.contains(".") ? column.substring(column.lastIndexOf(".") + 1) : column;
            String tableAlias = column.contains(".") ? column.substring(0, column.indexOf(".")) : "t";

            String replacement = String.format(
                "SELECT (SELECT %s FROM %s ORDER BY %s LIMIT 1) INTO STRICT %s FROM",
                cleanColumn, tableAlias, orderByClause, targetVar
            );

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}