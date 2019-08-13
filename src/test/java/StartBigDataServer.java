import bdv.server.BigDataServer;

public class StartBigDataServer
{
	public static void main( String[] args ) throws Exception
	{
		final String[] parameters = {
				"-d",
				"/Users/tischer/Documents/bigdataviewer-server/src/test/resources/datasets.txt" };
		BigDataServer.main( parameters );
	}
}
