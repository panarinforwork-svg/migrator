package migrator.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import migrator.Application;
import migrator.issues.DbmsLoad;
import migrator.issues.FixRecursiveCTE;
import migrator.issues.InitializationBlock;
import migrator.issues.Issue;
import migrator.issues.MergeInto;
import migrator.issues.UtlHttp;
import migrator.params.Parametrs;
import utils.FilesUtils;

public class Searcher {
	public final String projectPath;
	private static final Logger LOGGER = LogManager.getLogger(Application.class);
	
	private List<Issue> issues = List.of(
			new InitializationBlock(), 
			new DbmsLoad(), 
			new UtlHttp(),
			new MergeInto(),
			new FixRecursiveCTE());
	
	private Map<Class<?>, List<String>> filesByIssues = new HashMap<>();
	
	public enum ScriptType{
		PACKAGE;
	}
	
	public Searcher(Parametrs params) {
		projectPath = params.path;
		issues.stream().forEach(x -> filesByIssues.put(x.getClass(), new ArrayList()));
	}
	
	public void searchScripts(ScriptType type) {
		switch (type) {
		case PACKAGE:
			packageReplacer();
			break;

		default:
			break;
		}
	}
	
	private void packageReplacer() {
	    List<Path> paths = FilesUtils.searchPackages(projectPath);
	    ExecutorService executor = Executors.newFixedThreadPool(5);
	    
	    // Отправляем задачи
	    paths.forEach(path -> executor.submit(() -> checkFile(path)));
	    
	    executor.shutdown();
	    
	    LOGGER.info("All {} files processed", paths.size());
	}
    
    public String checkFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String result = content;
            for (Issue isu : issues) {
            	String tmp = isu.correct(content);
            	if (!tmp.equals(result)) {
            		filesByIssues.get(isu.getClass()).add(path.toString());
            		result = tmp;
            	}
            }
            if (!result.equals(content)) {
            	LOGGER.info(path.toString().concat(" needs to change"));
            	Files.write( path, content.getBytes());
            }
            return result;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
