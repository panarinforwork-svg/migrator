package utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Collections;

public class FilesUtils {
    
    private static final Logger LOGGER = LogManager.getLogger(FilesUtils.class);
    
    public static List<Path> searchPackages(String projectPath) {
        String packagePath = projectPath + "/schema/packages";
        LOGGER.debug("Searching package scripts in: {}", packagePath);
        
        // Используем packagePath вместо projectPath
        List<Path> corruptedFiles = findCorruptedFiles(Path.of(packagePath), "sql");
        LOGGER.info("Found {} SQL files to check", corruptedFiles.size());
        return corruptedFiles;
    }
    
    private static List<Path> findCorruptedFiles(Path startDir, String extension) {
        if (!Files.isDirectory(startDir)) {
            LOGGER.error("Path is not a directory: {}", startDir);
            return Collections.emptyList();
        }
        
        String normalizedExtension = extension.startsWith(".") ? extension.substring(1) : extension;
        
        try (Stream<Path> walk = Files.walk(startDir)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String fileName = path.getFileName().toString();
                    int dotIndex = fileName.lastIndexOf('.');
                    if (dotIndex == -1) return false;
                    String fileExtension = fileName.substring(dotIndex + 1).toLowerCase();
                    return fileExtension.equals(normalizedExtension.toLowerCase());
                })
                .collect(Collectors.toList());
            
            LOGGER.debug("Found {} files with extension '{}' in directory: {}", 
                files.size(), normalizedExtension, startDir);
            return files;
            
        } catch (IOException e) {
            LOGGER.error("Error walking file system from directory: {}", startDir, e);
            return Collections.emptyList();
        }
    }
}
