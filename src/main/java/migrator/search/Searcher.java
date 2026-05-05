package migrator.search;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import migrator.dblink.DblinkAnalyzer;
import migrator.issues.CallReorder;
import migrator.issues.ConnectByLevel;
import migrator.issues.DblinkToFdw;
import migrator.issues.DbmsLoad;
import migrator.issues.DbmsSession;
import migrator.issues.FixInsertRecordSyntaxAdvanced;
import migrator.issues.FixRecursiveCTE;
import migrator.issues.InitializationBlock;
import migrator.issues.Issue;
import migrator.issues.KeepClause;
import migrator.issues.LevelClause;
import migrator.issues.MergeInto;
import migrator.issues.ParameterReorder;
import migrator.issues.ProcedureCall;
import migrator.issues.RemoveInsertAlias;
import migrator.issues.ReplaceOpenCommentWithComment;
import migrator.issues.SubstrNamedParams;
import migrator.issues.UtlHttp;
import migrator.params.Parametrs;
import utils.FilesUtils;

public class Searcher {
	public final String projectPath;
	private static final Logger LOGGER = LogManager.getLogger(Searcher.class);
	private final DblinkAnalyzer dblinkAnalyz = new DblinkAnalyzer();
	
	private List<Issue> issues = List.of(
			new InitializationBlock(), 
//			new SubstrNamedParams(),
			new DbmsLoad(), 
			new UtlHttp(),
			new MergeInto(),
			new FixRecursiveCTE(),
			new ParameterReorder(),
			new DbmsSession(),
			new ProcedureCall(),
			new RemoveInsertAlias(),
			new ReplaceOpenCommentWithComment(),
			new FixInsertRecordSyntaxAdvanced(),
			new KeepClause(),
//			new LevelClause(),
			new DblinkToFdw(),
			new ConnectByLevel()
			);
	
	private List<Issue> postApplyIssues = List.of(
			new CallReorder(),
			new ProcedureCall()
			);
	
	private Map<Class<?>, List<String>> filesByIssues = new HashMap<>();
	
	public enum ScriptType{
		PACKAGE;
	}
	
	public Searcher(Parametrs params) {
		projectPath = params.path;
		issues.stream().forEach(x -> filesByIssues.put(x.getClass(), new ArrayList()));
		postApplyIssues.stream().forEach(x -> filesByIssues.put(x.getClass(), new ArrayList()));
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
	    
	    // Первый проход (issues)
	    for (Path path : paths) {
	    	try {
				dblinkAnalyz.analyzeFile(path.toString());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	        executor.submit(() -> checkFileAndWrite(path, issues));
	    }
	    executor.shutdown();
	    String sqlScript = dblinkAnalyz.generateSqlScript();
	    String filePath = projectPath + "/dblink.sql"; // можно указать полный путь
	    
	    try (BufferedWriter writer = new BufferedWriter(
	            new OutputStreamWriter(new FileOutputStream(filePath), StandardCharsets.UTF_8))) {
	        writer.write(sqlScript);
	        System.out.println("Файл сохранён: " + filePath);
	    } catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	    try {
	        executor.awaitTermination(1, TimeUnit.HOURS);
	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	    }
	    
	    // Второй проход (postApplyIssues) - читаем уже изменённые файлы
	    executor = Executors.newFixedThreadPool(5);
	    for (Path path : paths) {
	        executor.submit(() -> checkFileAndWrite(path, postApplyIssues));
	    }
	    executor.shutdown();
	    try {
	        executor.awaitTermination(1, TimeUnit.HOURS);
	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	    }
	}

	private void checkFileAndWrite(Path path, List<Issue> issuesToApply) {
	    try {
	        String content = Files.readString(path, StandardCharsets.UTF_8);
	        String result = content;
	        for (Issue isu : issuesToApply) {
	            String tmp = isu.correct(result);
	            if (!tmp.equals(result)) {
	                filesByIssues.get(isu.getClass()).add(path.toString());
	                result = tmp;
	            }
	        }
	        if (!result.equals(content)) {
	            LOGGER.info(path.toString().concat(" needs to change"));
	            Files.writeString(path, result, StandardCharsets.UTF_8);
	        }
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}

	public void checkFile(Path path) {
	    checkFileAndWrite(path, issues);
//	    checkFileAndWrite(path, postApplyIssues);
	}
}
