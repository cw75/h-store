package org.voltdb;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.voltdb.benchmark.tpcc.procedures.neworder;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.exceptions.MispredictionException;

import edu.brown.BaseTestCase;
import edu.brown.catalog.CatalogUtil;
import edu.brown.utils.ClassUtil;
import edu.brown.utils.ProjectType;
import edu.brown.workload.QueryTrace;
import edu.brown.workload.TransactionTrace;
import edu.brown.workload.Workload;
import edu.brown.workload.filters.BasePartitionTxnFilter;
import edu.brown.workload.filters.MultiPartitionTxnFilter;
import edu.brown.workload.filters.ProcedureLimitFilter;
import edu.brown.workload.filters.ProcedureNameFilter;

public class TestBatchPlannerComplex extends BaseTestCase {

    private static final Class<? extends VoltProcedure> TARGET_PROCEDURE = neworder.class;
    private static final int TARGET_BATCH = 1;
    private static final int WORKLOAD_XACT_LIMIT = 1;
    private static final int NUM_PARTITIONS = 50;
    private static final int BASE_PARTITION = 0;
    private static final long TXN_ID = 123l;
    private static final long CLIENT_HANDLE = Long.MAX_VALUE;

    private static Procedure catalog_proc;
    private static Workload workload;

    private static SQLStmt batch[][];
    private static ParameterSet args[][];
    private static List<QueryTrace> query_batch[];
    private static TransactionTrace txn_trace;
    
    private MockExecutionSite executor;

    @Override
    @SuppressWarnings("unchecked")
    protected void setUp() throws Exception {
        super.setUp(ProjectType.TPCC);
        this.addPartitions(NUM_PARTITIONS);

        if (workload == null) {
            catalog_proc = this.getProcedure(TARGET_PROCEDURE);
            
            File file = this.getWorkloadFile(ProjectType.TPCC, "100w.large");
            workload = new Workload(catalog);

            // Check out this beauty:
            // (1) Filter by procedure name
            // (2) Filter to only include multi-partition txns
            // (3) Another limit to stop after allowing ### txns
            // Where is your god now???
            Workload.Filter filter = new ProcedureNameFilter()
                    .include(TARGET_PROCEDURE.getSimpleName())
                    .attach(new BasePartitionTxnFilter(p_estimator, BASE_PARTITION))
                    .attach(new MultiPartitionTxnFilter(p_estimator))
                    .attach(new ProcedureLimitFilter(WORKLOAD_XACT_LIMIT));
            workload.load(file.getAbsolutePath(), catalog_db, filter);
            assert(workload.getTransactionCount() > 0);
            
            // Convert the first QueryTrace batch into a SQLStmt+ParameterSet batch
            txn_trace = workload.getTransactions().get(0);
            assertNotNull(txn_trace);
            int num_batches = txn_trace.getBatchCount();
            query_batch = (List<QueryTrace>[])new List<?>[num_batches];
            batch = new SQLStmt[num_batches][];
            args = new ParameterSet[num_batches][];
            
            for (int i = 0; i < query_batch.length; i++) {
                query_batch[i] = txn_trace.getBatchQueries(i);
                batch[i] = new SQLStmt[query_batch[i].size()];
                args[i] = new ParameterSet[query_batch[i].size()];
                for (int ii = 0; ii < batch[i].length; ii++) {
                    QueryTrace query_trace = query_batch[i].get(ii);
                    assertNotNull(query_trace);
                    batch[i][ii] = new SQLStmt(query_trace.getCatalogItem(catalog_db));
                    args[i][ii] = VoltProcedure.getCleanParams(batch[i][ii], query_trace.getParams());
                } // FOR
            } // FOR
        }
        
        VoltProcedure volt_proc = ClassUtil.newInstance(TARGET_PROCEDURE, new Object[0], new Class<?>[0]);
        assert(volt_proc != null);
        this.executor = new MockExecutionSite(BASE_PARTITION, catalog, p_estimator);
        volt_proc.globalInit(this.executor, catalog_proc, BackendTarget.NONE, null, CatalogUtil.getCluster(catalog_proc), p_estimator, BASE_PARTITION);
    }
    
    /**
     * testBatchHashCode
     */
    public void testBatchHashCode() throws Exception {
        final List<SQLStmt> statements = new ArrayList<SQLStmt>();
        for (Statement catalog_stmt : catalog_proc.getStatements()) {
            statements.add(new SQLStmt(catalog_stmt));
        } // FOR
        int num_stmts = statements.size();
        assert(num_stmts > 0);
        
        SQLStmt batches[][] = new SQLStmt[10][];
        int hashes[] = new int[batches.length];
        Random rand = new Random();
        for (int i = 0; i < batches.length; i++) {
            int batch_size = rand.nextInt(num_stmts - 1) + 1; 
            batches[i] = new SQLStmt[batch_size];
            Collections.shuffle(statements, rand);
            for (int ii = 0; ii < batch_size; ii++) {
                batches[i][ii] = statements.get(ii);
            } // FOR
            hashes[i] = VoltProcedure.getBatchHashCode(batches[i], batch_size);
            this.executor.batch_planners.put(hashes[i], new BatchPlanner(batches[i], catalog_proc, p_estimator));
        } // FOR
        
        for (int i = 0; i < batches.length; i++) {
            for (int ii = i+1; ii < batches.length; ii++) {
                assertNotSame(hashes[i], hashes[ii]);
                if (hashes[i] == hashes[ii]) {
                    for (SQLStmt s : batches[i])
                        System.err.println(s.catStmt.fullName());
                    System.err.println("---------------------------------------");
                    for (SQLStmt s : batches[ii])
                        System.err.println(s.catStmt.fullName());
                }
                assert(hashes[i] != hashes[ii]) : Arrays.toString(batches[i]) + "\n" + Arrays.toString(batches[ii]);
            } // FOR
            
            int hash = VoltProcedure.getBatchHashCode(batches[i], batches[i].length-1);
            assert(hashes[i] != hash);
            assertNull(this.executor.batch_planners.get(hash));
        } // FOR
    }
    
    /**
     * testPlanMultiPartition
     */
    public void testPlanMultiPartition() throws Exception {
        BatchPlanner batchPlan = new BatchPlanner(batch[TARGET_BATCH], catalog_proc, p_estimator);
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, BASE_PARTITION, args[TARGET_BATCH], false);
        assertNotNull(plan);
    }
    
    /**
     * testMispredict
     */
    public void testMispredict() throws Exception {
        BatchPlanner batchPlan = new BatchPlanner(batch[TARGET_BATCH], catalog_proc, p_estimator);
        
        // Ask the planner to plan a multi-partition transaction where we have predicted it
        // as single-partitioned. It should throw a nice MispredictionException
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, BASE_PARTITION+1, args[TARGET_BATCH], true);
        assert(plan.hasMisprediction());
        if (plan != null) System.err.println(plan.toString());
    }
    
    /**
     * testGetStatementPartitions
     */
    public void testGetStatementPartitions() throws Exception {
        for (int batch_idx = 0; batch_idx < query_batch.length; batch_idx++) {
            BatchPlanner batchPlan = new BatchPlanner(batch[batch_idx], catalog_proc, p_estimator);
            BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, BASE_PARTITION, args[batch_idx], false);
            assertNotNull(plan);
            
            Statement catalog_stmts[] = batchPlan.getStatements();
            assertNotNull(catalog_stmts);
            assertEquals(query_batch[batch_idx].size(), catalog_stmts.length);
            
            Set<Integer> partitions[] = plan.getStatementPartitions();
            assertNotNull(partitions);
            
            for (int i = 0; i < catalog_stmts.length; i++) {
                assertEquals(query_batch[batch_idx].get(i).getCatalogItem(catalog_db), catalog_stmts[i]);
                Set<Integer> p = partitions[i];
                assertNotNull(p);
                assertFalse(p.isEmpty());
            } // FOR
//            System.err.println(plan);
        }
        
    }
    
}
