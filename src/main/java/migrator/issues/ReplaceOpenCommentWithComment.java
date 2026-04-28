package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReplaceOpenCommentWithComment implements Issue {

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        // Ищем %OPEN_COMMENT( и заменяем на /*
        Pattern pattern = Pattern.compile(
            "%OPEN_COMMENT\\(",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher m = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        
        while (m.find()) {
            m.appendReplacement(sb, "/*");
        }
        m.appendTail(sb);
        
        return sb.toString();
    }
}
