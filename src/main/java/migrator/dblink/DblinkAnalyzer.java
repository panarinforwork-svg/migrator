package migrator.dblink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DblinkAnalyzer {
    
    private Set<String> foundDblinks = new HashSet<>();
    private Map<String, Set<String>> dblinkSchemas = new HashMap<>();
    private Map<String, Map<String, Set<String>>> dblinkTables = new HashMap<>();
    
    /**
     * Анализирует файл и собирает все dblink
     */
    public void analyzeFile(String filePath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(filePath)));
        analyzeContent(content);
    }
    
    public void analyzeContent(String content) {
        Pattern pattern = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*@\\s*([a-zA-Z_][a-zA-Z0-9_]*)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String schema = matcher.group(1);
            String table = matcher.group(2);
            String dblink = matcher.group(3);
            
            foundDblinks.add(dblink);
            dblinkSchemas.computeIfAbsent(dblink, k -> new HashSet<>()).add(schema);
            dblinkTables.computeIfAbsent(dblink, k -> new HashMap<>())
                       .computeIfAbsent(schema, k -> new HashSet<>())
                       .add(table);
        }
    }
    
    public String generateSqlScript() {
        StringBuilder sql = new StringBuilder();
        
        sql.append("-- =====================================================\n");
        sql.append("-- FDW SETUP (без view)\n");
        sql.append("-- =====================================================\n\n");
        
        for (String dblink : foundDblinks) {
            sql.append(generateMinimalScript(dblink));
            sql.append("\n");
        }
        
        sql.append(generateVerificationQueries());
        
        return sql.toString();
    }
    
    private String generateMinimalScript(String dblink) {
        StringBuilder sb = new StringBuilder();
        
        Set<String> schemas = dblinkSchemas.get(dblink);
        Map<String, Set<String>> schemasTables = dblinkTables.get(dblink);
        
        sb.append("-- =====================================================\n");
        sb.append("-- FDW Setup for ").append(dblink).append("\n");
        sb.append("-- =====================================================\n\n");
        
        // Очистка всех схем для этого dblink
        sb.append("-- Очистка\n");
        for (String schema : schemas) {
            String localSchema = dblink + "_" + schema;
            sb.append("DROP SCHEMA IF EXISTS ").append(localSchema).append(" CASCADE;\n");
        }
        sb.append("DROP SERVER IF EXISTS ").append(dblink).append("_server CASCADE;\n\n");
        
        // Создание
        sb.append("-- Создание\n");
        sb.append("CREATE EXTENSION IF NOT EXISTS postgres_fdw;\n\n");
        
        // Создаем все схемы
        sb.append("-- Схемы\n");
        for (String schema : schemas) {
            String localSchema = dblink + "_" + schema;
            sb.append("CREATE SCHEMA IF NOT EXISTS ").append(localSchema).append(";\n");
        }
        sb.append("\n");
        
        // Сервер (один для всех схем)
        sb.append("-- Сервер\n");
        sb.append("CREATE SERVER ").append(dblink).append("_server\n");
        sb.append("    FOREIGN DATA WRAPPER postgres_fdw\n");
        sb.append("    OPTIONS (\n");
        sb.append("        host 'localhost',\n");
        sb.append("        port '5432',\n");
        sb.append("        dbname 'postgres'\n");
        sb.append("    );\n\n");
        
        // Доступ для всех
        sb.append("-- Доступ для всех\n");
        sb.append("GRANT USAGE ON FOREIGN SERVER ").append(dblink).append("_server TO PUBLIC;\n\n");
        
        // User mapping для всех (один для всех схем)
        sb.append("-- User mapping для всех\n");
        sb.append("CREATE USER MAPPING FOR PUBLIC\n");
        sb.append("    SERVER ").append(dblink).append("_server\n");
        sb.append("    OPTIONS (\n");
        sb.append("        user 'postgres',\n");
        sb.append("        password_required 'false'\n");
        sb.append("    );\n\n");
        
        // Импорт таблиц для каждой схемы
        sb.append("-- Импорт таблиц\n");
        for (String schema : schemas) {
            String localSchema = dblink + "_" + schema;
            sb.append("IMPORT FOREIGN SCHEMA ").append(schema).append("\n");
            sb.append("    FROM SERVER ").append(dblink).append("_server\n");
            sb.append("    INTO ").append(localSchema).append(";\n\n");
        }
        
        // Права для всех на каждую схему
        sb.append("-- Права для всех\n");
        for (String schema : schemas) {
            String localSchema = dblink + "_" + schema;
            sb.append("GRANT USAGE ON SCHEMA ").append(localSchema).append(" TO PUBLIC;\n");
            sb.append("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA ").append(localSchema).append(" TO PUBLIC;\n");
        }
        sb.append("\n");
        
        // Проверки для каждой схемы
        sb.append("-- Проверки\n");
        for (Map.Entry<String, Set<String>> entry : schemasTables.entrySet()) {
            String schema = entry.getKey();
            Set<String> tables = entry.getValue();
            String localSchema = dblink + "_" + schema;
            if (tables != null && !tables.isEmpty()) {
                String firstTable = tables.iterator().next();
                sb.append("SELECT COUNT(*) AS ").append(schema).append("_count FROM ").append(localSchema).append(".").append(firstTable).append(";\n");
            }
        }
        
        return sb.toString();
    }
    
    private String generateVerificationQueries() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("\n-- =====================================================\n");
        sb.append("-- Verification queries\n");
        sb.append("-- =====================================================\n\n");
        
        sb.append("-- Проверка foreign servers\n");
        sb.append("SELECT srvname, srvoptions FROM pg_foreign_server;\n\n");
        
        sb.append("-- Проверка всех foreign tables\n");
        sb.append("SELECT \n");
        sb.append("    n.nspname AS schema_name,\n");
        sb.append("    c.relname AS table_name\n");
        sb.append("FROM pg_class c\n");
        sb.append("JOIN pg_namespace n ON c.relnamespace = n.oid\n");
        sb.append("WHERE n.nspname LIKE 'dec7_%'\n");
        sb.append("  AND c.relkind = 'f'\n");
        sb.append("ORDER BY n.nspname, c.relname;\n");
        
        return sb.toString();
    }
    
    public void printReport() {
        System.out.println("=== DBLink Analysis Report ===\n");
        System.out.println("Found " + foundDblinks.size() + " dblink(s):\n");
        
        for (String dblink : foundDblinks) {
            System.out.println("DBLink: " + dblink);
            Map<String, Set<String>> schemasTables = dblinkTables.get(dblink);
            
            if (schemasTables != null) {
                for (Map.Entry<String, Set<String>> entry : schemasTables.entrySet()) {
                    String schema = entry.getKey();
                    Set<String> tables = entry.getValue();
                    System.out.println("  Remote schema: " + schema);
                    System.out.println("    Local schema: " + dblink + "_" + schema);
                    System.out.println("    Tables: " + String.join(", ", tables));
                }
            }
            System.out.println();
        }
    }
    
    public Set<String> getFoundDblinks() {
        return foundDblinks;
    }
    
    public Map<String, Set<String>> getDblinkSchemas() {
        return dblinkSchemas;
    }
    
    public Map<String, Map<String, Set<String>>> getDblinkTables() {
        return dblinkTables;
    }
}