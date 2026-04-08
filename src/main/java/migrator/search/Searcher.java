package migrator.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
	
	
	
	public enum ScriptType{
		PACKAGE;
	}
	
	public Searcher(Parametrs params) {
		projectPath = params.path;
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
            String result = "";
            for (Issue isu : issues) {
            	content = isu.correct(content);
            }
            if (!result.equals(content)) {
            	LOGGER.info(path.toString() + " needs to change");
            }
            Files.write( path, content.getBytes());
            return content;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
