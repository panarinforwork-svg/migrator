package migrator.issues;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UtlHttp implements Issue {
    
    @Override
    public String correct(String content) {
        if (content == null || content.isBlank()) return content;
        
        String result = content;
        
        // Обрабатываем различные utl_http функции
        result = processUtlHttpSetTransferTimeout(result);
        result = processUtlHttpSetWallet(result);
        result = processUtlHttpSetHeader(result);
        result = processUtlHttpSetResponseErrorCheck(result);
        
        return result;
    }
    
    private String processUtlHttpSetTransferTimeout(String content) {
        Pattern pattern = Pattern.compile(
            "utl_http\\.set_transfer_timeout\\s*\\(\\s*([^;()]+(?:\\('[^']*'[^;()]*)?)\\s*\\)\\s*;",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String timeout = matcher.group(1).trim();
            String formattedValue = formatValue(timeout);
            String replacement = String.format(
                "PERFORM http_set_curlopt('CURLOPT_TIMEOUT', %s);",
                formattedValue
            );
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String processUtlHttpSetWallet(String content) {
        // utl_http.set_wallet('file:/path', 'password') -> PERFORM http_set_wallet('file:/path', 'password');
        Pattern pattern = Pattern.compile(
            "utl_http\\.set_wallet\\s*\\(\\s*('[^']*')\\s*,\\s*('[^']*')\\s*\\)\\s*;",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String path = matcher.group(1);
            String password = matcher.group(2);
            String replacement = String.format(
                "PERFORM http_set_wallet(%s, %s);",
                path, password
            );
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String processUtlHttpSetHeader(String content) {
        // utl_http.set_header(http_req, 'Content-Type', 'application/json');
        Pattern pattern = Pattern.compile(
            "utl_http\\.set_header\\s*\\(\\s*([^,]+)\\s*,\\s*('[^']*')\\s*,\\s*('[^']*')\\s*\\)\\s*;",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String request = matcher.group(1).trim();
            String name = matcher.group(2);
            String value = matcher.group(3);
            String replacement = String.format(
                "PERFORM http_set_header(%s, %s, %s);",
                request, name, value
            );
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String processUtlHttpSetResponseErrorCheck(String content) {
        // utl_http.set_response_error_check(TRUE);
        Pattern pattern = Pattern.compile(
            "utl_http\\.set_response_error_check\\s*\\(\\s*(TRUE|FALSE|true|false|1|0)\\s*\\)\\s*;",
            Pattern.CASE_INSENSITIVE
        );
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String enable = matcher.group(1);
            String replacement = String.format(
                "PERFORM http_set_response_error_check(%s);",
                enable
            );
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Форматирует значение: если это число - обрамляет кавычками, если переменная - оставляет как есть
     */
    private String formatValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        
        String trimmed = value.trim();
        
        // Если это число (целое или десятичное)
        if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
            return "'" + trimmed + "'";
        }
        
        // Если это уже строка в кавычках - оставляем как есть
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed;
        }
        
        // Если это NULL - оставляем без кавычек
        if (trimmed.equalsIgnoreCase("null")) {
            return trimmed;
        }
        
        // Если это булево значение - оставляем без кавычек
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return trimmed;
        }
        
        // В остальных случаях считаем, что это переменная или выражение
        // Проверяем, похоже на переменную (буквы, цифры, подчеркивания)
        if (trimmed.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return trimmed; // переменная без кавычек
        }
        
        // Если это выражение (содержит операторы или функции)
        if (trimmed.matches(".*[+\\-*/%()=<>].*")) {
            return trimmed; // выражение без кавычек
        }
        
        // По умолчанию считаем, что это переменная или выражение
        return trimmed;
    }
}