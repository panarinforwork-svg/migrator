package migrator.issues;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import com.tshprecher.postgres.antlr4.PostgreSQLLexer;
import com.tshprecher.postgres.antlr4.PostgreSQLParser;

public class RecordIssue implements Issue {

    private Set<String> recordVariables = new HashSet<>();
    private StringBuilder output = new StringBuilder();
    private String originalContent;
    private int lastIndex = 0;
    
    public String correct(String content) {
        try {
            this.originalContent = content;
            this.recordVariables.clear();
            this.output = new StringBuilder();
            this.lastIndex = 0;
            
            PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(content));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            PostgreSQLParser parser = new PostgreSQLParser(tokens);
            PostgreSQLParser.RootContext root = parser.root();
            
            collectRecordVariables(root);
            transform(root);
            output.append(originalContent.substring(lastIndex));
            
            return output.toString();
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
            return content;
        }
    }
    
    private void collectRecordVariables(ParseTree tree) {
        String text = tree.getText();
        Pattern p = Pattern.compile("\\s+(\\w+)\\s+RECORD\\b", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            recordVariables.add(m.group(1));
            System.out.println("Найдена RECORD: " + m.group(1));
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectRecordVariables(tree.getChild(i));
        }
    }
    
    private void transform(ParseTree tree) {
        String className = tree.getClass().getSimpleName();
        if (className.contains("Insert_stmt")) {
            transformInsert((PostgreSQLParser.Insert_stmtContext) tree);
            return;
        }
        if (isProcedureCallNode(tree)) {
            transformProcedureCall((ParserRuleContext) tree);
            return;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            transform(tree.getChild(i));
        }
    }
    
    private void transformInsert(PostgreSQLParser.Insert_stmtContext ctx) {
        int start = ctx.start.getStartIndex();
        int stop = ctx.stop.getStopIndex();
        output.append(originalContent.substring(lastIndex, start));
        
        String insertText = originalContent.substring(start, stop + 1);
        String newInsert = fixInsertText(insertText);
        output.append(newInsert);
        if (!insertText.equals(newInsert)) {
            System.out.println("Заменён INSERT: " + insertText + " -> " + newInsert);
        }
        lastIndex = stop + 1;
    }
    
    private String fixInsertText(String insertText) {
        Pattern p = Pattern.compile(
            "(?i)(INSERT\\s+INTO\\s+(\\w+))\\s+VALUES\\s*\\(\\s*(\\w+)\\s*\\)(.*)",
            Pattern.DOTALL
        );
        Matcher m = p.matcher(insertText);
        if (m.find()) {
            String tableName = m.group(2);
            String recordVar = m.group(3);
            String rest = m.group(4);
            if (recordVariables.contains(recordVar)) {
                return "INSERT INTO " + tableName + " SELECT (" + recordVar + ").*" + rest;
            }
        }
        return insertText;
    }
    
    private boolean isProcedureCallNode(ParseTree tree) {
        String className = tree.getClass().getSimpleName();
        if (className.contains("Func_call") || className.contains("Function_call")) {
            String text = tree.getText();
            if (text.contains(".")) {
                ParseTree parent = tree.getParent();
                while (parent != null) {
                    if (parent.getClass().getSimpleName().contains("Create_procedure")) {
                        return false;
                    }
                    parent = parent.getParent();
                }
                return true;
            }
        }
        return false;
    }
    
    private void transformProcedureCall(ParserRuleContext ctx) {
        int start = ctx.start.getStartIndex();
        int stop = ctx.stop.getStopIndex();
        output.append(originalContent.substring(lastIndex, start));
        
        String callText = originalContent.substring(start, stop + 1);
        if (!callText.trim().toUpperCase().startsWith("CALL")) {
            output.append("CALL ").append(callText);
            System.out.println("Добавлен CALL: " + callText);
        } else {
            output.append(callText);
        }
        lastIndex = stop + 1;
    }
}