package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DbmsSession implements Issue {


    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        // Паттерн для поиска dbms_session.set_context(арг1, арг2, арг3);
        // Группы: 1 - контекст, 2 - имя переменной, 3 - значение
        Pattern pattern = Pattern.compile(
            "\\bdbms_session\\.set_context\\s*\\(\\s*([^,]+?)\\s*,\\s*([^,]+?)\\s*,\\s*([^,]+?)\\s*\\)\\s*;",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            // Проверяем, что вызов не внутри комментария
            if (isInsideComment(content, matcher.start())) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            String ctx = matcher.group(1).trim();
            String varName = matcher.group(2).trim();
            String value = matcher.group(3).trim();

            // Формируем замену: PERFORM set_config(контекст || '.' || переменная, значение::text, false);
            String replacement = String.format(
                "PERFORM set_config(%s || '.' || %s, %s::text, false);",
                ctx, varName, value
            );
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Проверяет, находится ли позиция внутри однострочного или многострочного комментария.
     */
    private boolean isInsideComment(String content, int position) {
        // Однострочный комментарий --
        int lastLineComment = content.lastIndexOf("--", position);
        if (lastLineComment != -1) {
            int endOfLine = content.indexOf("\n", lastLineComment);
            if (endOfLine == -1) endOfLine = content.length();
            if (position > lastLineComment && position < endOfLine) {
                return true;
            }
        }

        // Многострочный комментарий /*
        int lastBlockCommentStart = content.lastIndexOf("/*", position);
        if (lastBlockCommentStart != -1) {
            int blockCommentEnd = content.indexOf("*/", lastBlockCommentStart);
            if (blockCommentEnd == -1) blockCommentEnd = content.length();
            if (position > lastBlockCommentStart && position < blockCommentEnd) {
                return true;
            }
        }

        return false;
    }
	}