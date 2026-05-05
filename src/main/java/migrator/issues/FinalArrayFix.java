package migrator.issues;

import java.util.*;
import java.util.regex.*;

public class FinalArrayFix implements Issue {

    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;

        content = fixInitExtend(content);
        content = fixParenToBracket(content);
        content = fixSelectIntoAndDeclare(content);
        content = fixAssignComments(content); // удаляем комментарии внутри присваиваний
        return content;
    }

    private String fixInitExtend(String content) {
        Pattern p = Pattern.compile(
                "(\\w+)\\s*:=\\s*vcarray\\(\\)\\s*;\\s*CALL\\s+\\1\\.extend\\((\\d+)\\)\\s*;",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        return p.matcher(content).replaceAll(m ->
                Matcher.quoteReplacement(m.group(1) + " := array_fill(NULL::varchar, ARRAY[" + m.group(2) + "]);"));
    }

    private String fixParenToBracket(String content) {
        Pattern p = Pattern.compile("v_results\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
        return p.matcher(content).replaceAll("v_results[$1]");
    }

    private String fixSelectIntoAndDeclare(String content) {
        Pattern selectPattern = Pattern.compile(
                "(?is)(select\\s+)(.*?)(\\s+into\\s+strict\\s+)(.*?)(\\s+from\\s+)(.*?);",
                Pattern.DOTALL);
        Matcher m = selectPattern.matcher(content);
        if (!m.find()) return content;
        m.reset();

        StringBuffer result = new StringBuffer();
        List<String> allTempVars = new ArrayList<>();
        Map<String, String> replacements = new LinkedHashMap<>();

        while (m.find()) {
            String selectKw = m.group(1);
            String selectBody = m.group(2);
            String intoKw = m.group(3);
            String intoRaw = m.group(4);
            String fromKw = m.group(5);
            String fromRest = m.group(6);

            List<String> intoElements = splitIntoElements(intoRaw);
            intoElements.removeIf(String::isBlank);

            List<String> arrayVars = new ArrayList<>();
            List<String> plainVars = new ArrayList<>();
            for (String e : intoElements) {
                String trimmed = e.trim();
                if (trimmed.startsWith("v_results[")) {
                    arrayVars.add(trimmed);
                } else {
                    plainVars.add(trimmed);
                }
            }
            if (arrayVars.isEmpty()) {
                result.append(m.group(0));
                continue;
            }

            List<String> columns = splitSelectColumns(selectBody);
            if (columns.size() != intoElements.size()) {
                result.append(m.group(0));
                continue;
            }

            List<String> tempVars = new ArrayList<>();
            for (int i = 0; i < arrayVars.size(); i++) {
                tempVars.add("_temp_arr_" + i);
                allTempVars.add("_temp_arr_" + i);
            }

            StringBuilder newSelect = new StringBuilder(selectKw);
            int arrayCounter = 0;
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i).trim();
                if (intoElements.get(i).trim().startsWith("v_results[")) {
                    col = removeAlias(col);
                    col = col + " AS " + tempVars.get(arrayCounter++);
                }
                newSelect.append(col);
                if (i < columns.size() - 1) newSelect.append(",");
                newSelect.append("\n");
            }

            StringBuilder intoList = new StringBuilder();
            for (String tv : tempVars) {
                if (intoList.length() > 0) intoList.append(", ");
                intoList.append(tv);
            }
            if (!plainVars.isEmpty()) {
                if (intoList.length() > 0) intoList.append(", ");
                intoList.append(String.join(", ", plainVars));
            }
            String newInto = intoKw + intoList.toString();

            String newSelectBlock = newSelect.toString() + newInto + "\n" + fromKw + fromRest + ";";

            StringBuilder assigns = new StringBuilder();
            for (int i = 0; i < arrayVars.size(); i++) {
                assigns.append(arrayVars.get(i)).append(" := ").append(tempVars.get(i)).append(";\n");
            }

            String replacement = newSelectBlock + "\n" + assigns.toString();
            replacements.put(m.group(0), replacement);
        }

        for (Map.Entry<String, String> e : replacements.entrySet()) {
            content = content.replace(e.getKey(), e.getValue());
        }

        if (!allTempVars.isEmpty()) {
            Pattern declareBlock = Pattern.compile(
                    "(DECLARE\\s+)(.*?)(BEGIN)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher dm = declareBlock.matcher(content);
            StringBuffer sb = new StringBuffer();
            if (dm.find()) {
                String declareStart = dm.group(1);
                String declareBody = dm.group(2);
                String begin = dm.group(3);
                for (String tv : allTempVars) {
                    declareBody = declareBody.replaceAll("\\s*" + tv + "\\s+varchar\\s*;", "");
                }
                StringBuilder newDeclarations = new StringBuilder();
                for (String tv : allTempVars) {
                    newDeclarations.append("    ").append(tv).append(" varchar;\n");
                }
                String newDeclareBody = declareBody + "\n" + newDeclarations.toString();
                dm.appendReplacement(sb, declareStart + newDeclareBody + begin);
                dm.appendTail(sb);
                content = sb.toString();
            }
        }

        return content;
    }

    // Удаляем однострочные комментарии внутри строк присваивания v_results[...] := ... ;
    private String fixAssignComments(String content) {
        // Ищем v_results[...] затем пробелы, затем комментарий, затем :=
        Pattern p = Pattern.compile(
                "(v_results\\[[^\\]]+\\])\\s*--[^\\n]*\\s*:=\\s*([^;]+);",
                Pattern.CASE_INSENSITIVE);
        StringBuffer sb = new StringBuffer();
        Matcher m = p.matcher(content);
        while (m.find()) {
            String replacement = m.group(1) + " := " + m.group(2) + ";";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private List<String> splitIntoElements(String list) {
        List<String> res = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : list.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                res.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) res.add(cur.toString());
        return res;
    }

    private List<String> splitSelectColumns(String selectBody) {
        String noComments = selectBody.replaceAll("--[^\\n]*", "");
        List<String> cols = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : noComments.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                cols.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) cols.add(cur.toString());
        for (int i = 0; i < cols.size(); i++) cols.set(i, cols.get(i).trim());
        return cols;
    }

    private String removeAlias(String expr) {
        return expr.replaceFirst("(?i)\\s+AS\\s+\\w+\\s*$", "");
    }
}