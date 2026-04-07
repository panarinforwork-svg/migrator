package migrator;

import java.nio.file.Path;

import migrator.params.Parametrs;
import migrator.search.Searcher;
import migrator.search.Searcher.ScriptType;

public class Application {
	public static void main(String[] args) {
		Parametrs params = new Parametrs(args);
		Searcher search = new Searcher(params);
//		search.searchScripts(ScriptType.PACKAGE);
		search.checkFile(Path.of("C:\\Users\\panarin\\Desktop\\svoe\\experiments\\migration\\schema\\packages\\signer_api_pkg\\get_package.sql"));
	}
}
