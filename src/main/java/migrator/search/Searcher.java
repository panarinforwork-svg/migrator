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

import migrator.params.Parametrs;

public class Searcher {
	public final String projectPath;
	
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
                .forEach(Searcher::checkFile);
        } catch (IOException e) {
            System.err.println("Ошибка обхода файловой системы: " + e.getMessage());
        }
    }
    
    public static String checkFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) return content;

            // Три списка для стартовых позиций
            final List<Integer> beginPositions = new ArrayList<>();
            final List<Integer> endPositions = new ArrayList<>();
            final List<Integer> bodyPositions = new ArrayList<>();
            
            final List<Integer> toDelete = new ArrayList<>();

            // Ищем все слова с их точными позициями в исходной строке
            Matcher m = Pattern.compile("[^\\s;]+").matcher(content);
            while (m.find()) {
                String word = m.group();
                int start = m.start(); // стартовая позиция слова
                if ("begin".equalsIgnoreCase(word)) {
                    beginPositions.add(start);
                } else if ("end".equalsIgnoreCase(word)) {
                	if (content.charAt(start + 3) == ';') {
                		endPositions.add(start);
                	}
                } else if ("$body$".equalsIgnoreCase(word)) {
                    bodyPositions.add(start);
                }
            }
            
            for (int i = bodyPositions.size() - 1; i >= 1; i -= 2) {
            	final int currentI = i;
            	List<Integer> begins = beginPositions.stream()
            	.filter(x -> x > bodyPositions.get(currentI-1) && x < bodyPositions.get(currentI))
            	.collect(Collectors.toList());
            	
            	List<Integer> ends = endPositions.stream()
            	.filter(x -> x > bodyPositions.get(currentI-1) && x < bodyPositions.get(currentI))
            	.collect(Collectors.toList());
            	
            	if (begins.size() > ends.size()) {
            		toDelete.add(begins.getLast());
            		toDelete.add(bodyPositions.get(i));
            	}
            }

            StringBuilder sb = new StringBuilder(content);
            if (!toDelete.isEmpty()) {
            	sb.delete(toDelete.get(0), toDelete.get(1));
            }
            String result = sb.toString();
            Files.write( path, result.getBytes());
            return result;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
