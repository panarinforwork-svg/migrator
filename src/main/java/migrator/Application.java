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
		search.checkFile(Path.of("C:\\Users\\panarin\\Desktop\\svoe\\valid-package\\error\\okso_key_words_pkg_package\\get_words_package.sql"));
	}
}
