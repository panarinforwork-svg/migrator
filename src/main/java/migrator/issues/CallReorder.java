package migrator.issues;

import java.util.*;
import java.util.regex.*;

import migrator.issues.ParameterReorder.MethodSignature;
import migrator.issues.ParameterReorder.Param;

public class CallReorder implements Issue {

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        // Берём актуальные сигнатуры на момент вызова
        Map<String, ParameterReorder.MethodSignature> signatures = ParameterReorder.getChangedSignatures();
        String result = content;
        
        for (Map.Entry<String, ParameterReorder.MethodSignature> entry : signatures.entrySet()) {
            String fullName = entry.getKey();
            ParameterReorder.MethodSignature sig = entry.getValue();
            result = processCalls(result, fullName, sig);
        }
        return result;
    }

    private String processCalls(String sql, String fullName, ParameterReorder.MethodSignature sig) {
        String simpleName = fullName.contains(".") ? fullName.substring(fullName.indexOf('.') + 1) : fullName;
        
        // Ищем вызовы, но НЕ внутри CREATE OR REPLACE PROCEDURE/FUNCTION и НЕ в REVOKE
        Pattern callPattern = Pattern.compile(
            "(?i)(?<!CREATE\\s+OR\\s+REPLACE\\s+(?:PROCEDURE|FUNCTION)\\s+)" +
            "(?<!REVOKE\\s+ALL\\s+ON\\s+(?:PROCEDURE|FUNCTION)\\s+)" +
            "(?:" + Pattern.quote(simpleName) + "|" + Pattern.quote(fullName) + ")\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE
        );

        StringBuffer sb = new StringBuffer();
        Matcher m = callPattern.matcher(sql);
        while (m.find()) {
            String argsRaw = m.group(1);
            List<String> originalArgs = splitArgs(argsRaw);
            
            // Проверяем, не находимся ли мы внутри CREATE или REVOKE
            String beforeMatch = sql.substring(0, m.start());
            if (isInsideCreateOrRevoke(beforeMatch)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            
            // Проверяем, что количество аргументов соответствует количеству параметров
            if (originalArgs.size() == sig.originalParams.size()) {
                List<String> reorderedArgs = reorderArguments(originalArgs, sig);
                String newCall = m.group(0).replace(argsRaw, String.join(", ", reorderedArgs));
                m.appendReplacement(sb, Matcher.quoteReplacement(newCall));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
    
    private boolean isInsideCreateOrRevoke(String textBefore) {
        // Ищем последний CREATE OR REPLACE или REVOKE перед вызовом
        int lastCreate = textBefore.lastIndexOf("CREATE OR REPLACE");
        int lastRevoke = textBefore.lastIndexOf("REVOKE");
        int lastSemicolon = textBefore.lastIndexOf(";");
        
        // Если после последней точки с запятой есть CREATE или REVOKE, значит мы внутри
        if (lastSemicolon > lastCreate && lastSemicolon > lastRevoke) {
            return false;
        }
        return (lastCreate > lastSemicolon || lastRevoke > lastSemicolon);
    }

    private List<String> splitArgs(String argsRaw) {
        List<String> args = new ArrayList<>();
        int parenLevel = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        StringBuilder current = new StringBuilder();
        
        for (char c : argsRaw.toCharArray()) {
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
            if (c == '(') {
                parenLevel++;
                current.append(c);
            } else if (c == ')') {
                parenLevel--;
                current.append(c);
            } else if (c == ',' && parenLevel == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) args.add(current.toString().trim());
        return args;
    }

    private List<String> reorderArguments(List<String> originalArgs, ParameterReorder.MethodSignature sig) {
        List<ParameterReorder.Param> orig = sig.originalParams;
        List<ParameterReorder.Param> reord = sig.reorderedParams;
        String[] newArgs = new String[reord.size()];
        Arrays.fill(newArgs, null);

        // Отображение: i-й аргумент соответствует i-му параметру в оригинале
        for (int i = 0; i < originalArgs.size() && i < orig.size(); i++) {
            ParameterReorder.Param p = orig.get(i);
            // Ищем параметр с таким же именем в переупорядоченном списке
            for (int j = 0; j < reord.size(); j++) {
                if (reord.get(j).name.equalsIgnoreCase(p.name)) {
                    newArgs[j] = originalArgs.get(i);
                    break;
                }
            }
        }
        
        // Убираем NULL значения (параметры с DEFAULT, которые не были переданы)
        List<String> result = new ArrayList<>();
        for (String arg : newArgs) {
            if (arg != null && !"NULL".equals(arg)) {
                result.add(arg);
            }
        }
        return result;
    }
}