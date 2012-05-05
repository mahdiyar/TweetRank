

import graph.TemporaryGraph;
import graph.PersistentGraph;
import httpserv.RequestHandler;
import httpserv.StatusHandler;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import com.sun.net.httpserver.HttpServer;
import computer.TweetRankComputer;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;


public class TweetRanker {
	private static final Logger logger = Logger.getLogger("ranker.logger");
	public static final int PORT = 4711;
	
	private static String name = "graph";
	private static String path = "../data/graph/";
	private static String RankingName = "../data/tweetrank.tr";
	private static long RankingPeriod = MinToMilli(30);  
	private static long StoringPeriod = MinToMilli(20);
	
	private HttpServer server;
	private Timer rankerTimer = new Timer();
	private Timer storeTimer = new Timer();
	private PersistentGraph graph;
	private TweetRankComputer ranker;
	
	/** Converts minutes to milliseconds. */
	private static long MinToMilli(long min) { return min*60000; }
	
	/** Rename a file. */
    private static boolean renameFile(String file, String toFile) {
        java.io.File toBeRenamed = new java.io.File(file);
        if (!toBeRenamed.exists() || toBeRenamed.isDirectory()) return false;
        java.io.File newFile = new java.io.File(toFile);
        return toBeRenamed.renameTo(newFile);
    }
    
    private static String generateTemporalFilename() {
    	return java.util.UUID.randomUUID().toString();
    }
	
	/** Shutdown hook. Saves the persistent information on disk. */
	private static class ShutdownThread implements Runnable {
		TweetRanker server;
		PersistentGraph graph;
		
		public ShutdownThread(TweetRanker server, PersistentGraph graph) {
			super();
			this.server = server;
			this.graph = graph;
		}

		@Override
		public void run() {
			logger.info("Closing...");
			this.server.stop();
			this.graph.store();
		}		
	}
	
	/** Periodic TweetRank computation task. */
	private class RankingComputationTask extends TimerTask {
		@Override
		public void run() {
			try {
				ranker.setTemporaryGraph(new TemporaryGraph(graph));  // Creates a new temporary graph 
				HashMap<Long,Double> pr = ranker.compute();           // Start computation!
				if ( pr != null ) { // If everything was OK, save the result on a file
					String tmpFile = "/tmp/" + generateTemporalFilename(); 
					FileWriter fWriter = new FileWriter(tmpFile); 
					BufferedWriter bWriter = new BufferedWriter(fWriter);
					for(Map.Entry<Long, Double> entry : pr.entrySet())
						bWriter.write(entry.getKey() + "\t" + entry.getValue() + "\n");
					bWriter.close();
					renameFile(tmpFile, RankingName);
				}
			} catch ( TweetRankComputer.ConcurrentComputationException e ) {
				logger.info("A TweetRank computation is already ongoing.");
			} catch (Throwable t) {
				logger.error("Error during the TweetRank computation.", t);
			}
		}
	}
	
	private class PersistentStoreTask extends TimerTask {
		@Override
		public void run() {
			logger.info("Storing persistent graph...");
			graph.store();
		}
	}

	public TweetRanker(InetSocketAddress addr, int backlog, PersistentGraph graph) throws IOException {
		super();
		this.graph = graph;
		this.ranker = new TweetRankComputer();
		server = HttpServer.create(addr, backlog);
		server.createContext("/status", new StatusHandler(graph));
		server.createContext("/", new RequestHandler(graph));
		server.setExecutor(null);
	}

	public void start() {
		server.start();
		rankerTimer.schedule(new RankingComputationTask(), RankingPeriod, RankingPeriod);
		storeTimer.schedule(new PersistentStoreTask(), StoringPeriod, StoringPeriod);
	}
	
	public void stop() {
		server.stop(0);
		rankerTimer.cancel();
		storeTimer.cancel();
	}

	public static void main(String[] args) {
		PersistentGraph graph = null;
		TweetRanker server = null;
		BasicConfigurator.configure();
		logger.setLevel(Level.INFO);

		try {	
			graph  = new PersistentGraph(name, path);
			server = new TweetRanker(new InetSocketAddress(TweetRanker.PORT), 15, graph);
			Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownThread(server, graph)));
		} catch (Throwable e) {
			logger.fatal("Error on initialization.", e);
		}
		
		logger.info("Ranker ready!");
		server.start();
	}
}

