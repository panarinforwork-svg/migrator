package migrator.params;

public class Parametrs {
	public String path;
	
	// TODO добавить валидацию
	public Parametrs(String[] args) {
		path = args[0];
	}
}
