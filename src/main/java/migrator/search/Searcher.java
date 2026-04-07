package migrator.search;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import migrator.issues.DbmsLoad;
import migrator.issues.InitializationBlock;
import migrator.issues.Issue;
import migrator.issues.MergeInto;
import migrator.issues.UtlHttp;
import migrator.params.Parametrs;

public class Searcher {
	public final String projectPath;
	
	private List<Issue> issues = List.of(
			new InitializationBlock(), 
			new DbmsLoad(), 
			new UtlHttp(),
			new MergeInto());
	
	
	public enum ScriptType{
		PACKAGE;
	}
	
	public Searcher(Parametrs params) {
		projectPath = params.path;
	}
	
	public void searchScripts(ScriptType type) {
		switch (type) {
		case PACKAGE:
			searchPackageScript();
			break;

		default:
			break;
		}
	}
	
	private void searchPackageScript() {
		String packagePath = projectPath + "/schema/packages";
		findCorruptedFiles(Path.of(projectPath), "sql");
	}
	
    public void findCorruptedFiles(Path startDir, String extension) {
        if (!Files.isDirectory(startDir)) {
            System.err.println("Путь не является директорией: " + startDir);
            return;
        }

        try (Stream<Path> walk = Files.walk(startDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(extension))
                .forEach(x -> this.checkFile(x));
        } catch (IOException e) {
            System.err.println("Ошибка обхода файловой системы: " + e.getMessage());
        }
    }
    
    public String checkFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            for (Issue isu : issues) {
            	content = isu.correct(content);
            }
            Files.write( path, content.getBytes());
            return content;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
