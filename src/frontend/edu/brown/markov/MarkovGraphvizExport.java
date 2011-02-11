package edu.brown.markov;

import java.util.Map;

import org.voltdb.catalog.Procedure;

import edu.brown.graphs.GraphvizExport;
import edu.brown.utils.ArgumentsParser;

public class MarkovGraphvizExport {

    /**
     * @param args
     */
    public static void main(String vargs[]) throws Exception {
        ArgumentsParser args = ArgumentsParser.load(vargs);
        args.require(ArgumentsParser.PARAM_CATALOG, ArgumentsParser.PARAM_MARKOV);
        
        String input_path = args.getParam(ArgumentsParser.PARAM_MARKOV);
        String proc_name = args.getOptParam(0);
        Procedure catalog_proc = args.catalog_db.getProcedures().getIgnoreCase(proc_name);
        assert(catalog_proc != null);
        
        // Assume global file for now...
        Map<Integer, MarkovGraphsContainer> m = MarkovUtil.load(args.catalog_db, input_path);
        MarkovGraphsContainer markovs = m.get(MarkovUtil.GLOBAL_MARKOV_CONTAINER_ID);
        assert(markovs != null);
        
        MarkovGraph markov = markovs.get(MarkovUtil.GLOBAL_MARKOV_CONTAINER_ID, catalog_proc);
        assert(markov.isValid()) : "The graph for " + catalog_proc + " is not initialized!";
        GraphvizExport<Vertex, Edge> gv = MarkovUtil.exportGraphviz(markov, true, null);
        System.err.println("WROTE FILE: " + gv.writeToTempFile(catalog_proc.getName()));

    }

}
