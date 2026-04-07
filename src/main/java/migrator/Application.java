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
		search.checkFile(Path.of("C:\\Users\\panarin\\Desktop\\svoe\\data\\migration2\\schema\\packages\\curriculum_diff_pkg\\set_disc_for_diff_curr_package.sql"));
	}
}
