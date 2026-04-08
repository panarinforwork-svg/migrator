package migrator.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class InitializationBlock implements Issue {

	@Override
	public String correct(String content) {
	    if (content == null || content.isBlank()) return content;

	    // Три списка для стартовых позиций
	    final List<Integer> beginPositions = new ArrayList<>();
	    final List<Integer> endPositions = new ArrayList<>();
	    final List<Integer> bodyPositions = new ArrayList<>();
	    
	    final List<Integer> toDelete = new ArrayList<>();

	    // Определяем позиции комментариев
	    List<int[]> commentRanges = getCommentRanges(content);
	    
	    // Ищем все слова с их точными позициями в исходной строке
	    Matcher m = Pattern.compile("[^\\s;]+").matcher(content);
	    while (m.find()) {
	        String word = m.group();
	        int start = m.start();
	        
	        // Пропускаем слова внутри комментариев
	        if (isInsideComment(start, commentRanges)) {
	            continue;
	        }
	        
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
	            toDelete.add(begins.get(begins.size()-1));
	            toDelete.add(bodyPositions.get(i));
	        }
	    }

	    StringBuilder sb = new StringBuilder(content);
	    if (!toDelete.isEmpty()) {
	        sb.delete(toDelete.get(0), toDelete.get(1));
	    }
	    return sb.toString();
	}

	private List<int[]> getCommentRanges(String content) {
	    List<int[]> ranges = new ArrayList<>();
	    
	    // Однострочные комментарии --
	    Pattern singleLine = Pattern.compile("--[^\\n]*");
	    Matcher m1 = singleLine.matcher(content);
	    while (m1.find()) {
	        ranges.add(new int[]{m1.start(), m1.end()});
	    }
	    
	    // Многострочные комментарии /* */
	    Pattern multiLine = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	    Matcher m2 = multiLine.matcher(content);
	    while (m2.find()) {
	        ranges.add(new int[]{m2.start(), m2.end()});
	    }
	    
	    return ranges;
	}

	private boolean isInsideComment(int position, List<int[]> commentRanges) {
	    for (int[] range : commentRanges) {
	        if (position >= range[0] && position < range[1]) {
	            return true;
	        }
	    }
	    return false;
	}

}
