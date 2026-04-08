package migrator;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import migrator.params.Parametrs;
import migrator.search.Searcher;
import migrator.search.Searcher.ScriptType;

public class Application {
    private static final Logger LOGGER = LogManager.getLogger(Application.class);
    
    public static void main(String[] args) {
        LOGGER.info("Application started");
        
        Parametrs params = new Parametrs(args);
        LOGGER.debug("Params initialized: {}", params);
        
        Searcher search = new Searcher(params);
        
        Path filePath = Path.of("C:\\Users\\panarin\\Desktop\\svoe\\experiments\\migration\\schema\\packages\\signer_api_pkg\\get_package.sql");
        LOGGER.info("Checking file: {}", filePath.getFileName());
        search.checkFile(filePath);
//        search.searchScripts(ScriptType.PACKAGE);
        
        LOGGER.info("Application finished");
    }
}
