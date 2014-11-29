package rvpredict.engine.main;

/*******************************************************************************
 * Copyright (c) 2013 University of Illinois
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import com.google.common.collect.Sets;

import rvpredict.config.Configuration;
import rvpredict.db.DBEngine;
import rvpredict.trace.Event;
import rvpredict.trace.MemoryAccessEvent;
import rvpredict.trace.ReadEvent;
import rvpredict.trace.SyncEvent;
import rvpredict.trace.Trace;
import rvpredict.trace.TraceInfo;
import rvpredict.trace.WriteEvent;
import rvpredict.util.Logger;
import smt.EngineSMTLIB1;
import violation.ExactRace;
import violation.Race;
import violation.Violation;

/**
 * The NewRVPredict class implements our new race detection algorithm based on
 * constraint solving. The events in the trace are loaded and processed window
 * by window with a configurable window size.
 *
 * @author jeffhuang
 *
 */
public class NewRVPredict {

    private HashSet<Violation> violations = new HashSet<Violation>();
    private HashSet<Violation> potentialviolations = new HashSet<Violation>();
    private Configuration config;
    private Logger logger;
    private HashMap<Integer, String> sharedVarIdSigMap = new HashMap<>();
    private Set<Integer> volatileFieldIds = new HashSet<>();
    private HashMap<Integer, String> stmtIdSigMap = new HashMap<>();
    private HashMap<Long, String> threadIdNameMap = new HashMap<>();
    private long totalTraceLength;
    private DBEngine dbEngine;
    private TraceInfo traceInfo;
    private long startTime;

    /**
     * Race detection method. For every pair of conflicting data accesses, the
     * corresponding race constraint is generated and solved by a solver. If the
     * solver returns a solution, we report a real data race. Otherwise, a
     * potential race is reported. We call it a potential race but not a false
     * race because it might be a real data race in another trace.
     *
     * @param engine
     * @param trace
     */
    private void detectRace(EngineSMTLIB1 engine, Trace trace) {
        // implement potentialraces to be exact match

        // sometimes we choose an un-optimized way to implement things faster,
        // easier
        // e.g., here we use check, but still enumerate read/write
        for (String addr : trace.getMemAccessEventsTable().rowKeySet()) {
            /* exclude volatile variable */
            if (config.novolatile && trace.isVolatileAddr(addr)) {
                continue;
            }

            List<ReadEvent> readEvents = trace.getReadEventsOn(addr);
            List<WriteEvent> writeEvents = trace.getWriteEventsOn(addr);
            /* skip if there is no write event */
            if (writeEvents.isEmpty()) {
                continue;
            }

            /* skip if there is only one thread */
            if (trace.getMemAccessEventsTable().row(addr).size() == 1) {
                continue;
            }

            // find equivalent reads and writes by the same thread
            Map<MemoryAccessEvent, Set<MemoryAccessEvent>> equiMap = new HashMap<>();
            // skip non-primitive and array variables?
            // because we add branch operations before their operations
            for (Entry<Long, List<MemoryAccessEvent>> entry : trace
                    .getMemAccessEventsTable().row(addr).entrySet()) {
                // TODO(YilongL): the extensive use of List#indexOf could be a performance problem later

                long crntTID = entry.getKey();
                List<MemoryAccessEvent> memAccEvents = entry.getValue();

                MemoryAccessEvent memAcc1 = memAccEvents.get(0);

                // the index of event `memAcc1' in current thread
                int memAcc1Idx = trace.getThreadEvents(crntTID).indexOf(memAcc1);

                for (int k = 1; k < memAccEvents.size(); k++) {
                    MemoryAccessEvent memAcc2 = memAccEvents.get(k);
                    List<Event> crntThrdEvents = trace.getThreadEvents(crntTID);
                    int memAcc2Idx = crntThrdEvents.indexOf(memAcc2);

                    boolean newEquiMemAccBlk = true;
                    if (memAcc2.getPrevBranchGID() < memAcc1.getGID()) {
                        /* there is no branch event between `memAcc1' and `memAcc2' */
                        boolean noSyncEvent = true;
                        for (int i = memAcc2Idx - 1; i > memAcc1Idx; i--) {
                            if (crntThrdEvents.get(i) instanceof SyncEvent) {
                                noSyncEvent = false;
                                break;
                            }
                        }

                        if (noSyncEvent) {
                            Set<MemoryAccessEvent> set = equiMap.get(memAcc1);
                            if (set == null) {
                                set = Sets.newHashSet();
                                equiMap.put(memAcc1, set);
                            }
                            set.add(memAcc2);
                            newEquiMemAccBlk = false;
                        }
                    }

                    if (newEquiMemAccBlk) {
                        memAcc1 = memAcc2;
                        memAcc1Idx = memAcc2Idx;
                    }
                }
            }

            // check read-write conflict
            if (readEvents != null)
                for (int i = 0; i < readEvents.size(); i++) {
                    ReadEvent rnode = readEvents.get(i);// read
                    // if(rnode.getGID()==3105224)//3101799
                    // System.out.println("");

                    for (int j = 0; j < writeEvents.size(); j++) {
                        WriteEvent wnode = writeEvents.get(j);// write

                        // check read and write are by different threads
                        if (rnode.getTID() != wnode.getTID()) {
                            // create a potential race
                            Race race = new Race(trace.getStmtSigIdMap().get(rnode.getID()), trace
                                    .getStmtSigIdMap().get(wnode.getID()), rnode.getID(),
                                    wnode.getID());
                            ExactRace race2 = new ExactRace(race, (int) rnode.getGID(),
                                    (int) wnode.getGID());
                            // skip redundant races with the same signature,
                            // i.e., from same program locations
                            if (config.allrace || !violations.contains(race)
                                    && !potentialviolations.contains(race2))// may
                                                                            // miss
                                                                            // real
                                                                            // violation
                                                                            // with
                                                                            // the
                                                                            // same
                                                                            // signature
                            {

                                // Quick check first: lockset algorithm + weak
                                // HB

                                // lockset algorithm
                                if (engine.hasCommonLock(rnode, wnode))
                                    continue;

                                // weak HB check
                                // a simple reachability analysis to reduce the
                                // solver invocations
                                if (rnode.getGID() < wnode.getGID()) {
                                    if (engine.canReach(rnode, wnode))
                                        continue;
                                } else {
                                    if (engine.canReach(wnode, rnode))
                                        continue;
                                }

                                // if(race.toString().equals("<mergesort.MSort: void DecreaseThreadCounter()>|$i0 = <mergesort.MSort: int m_iCurrentThreadsAlive>|41 - <mergesort.MSort: void DecreaseThreadCounter()>|<mergesort.MSort: int m_iCurrentThreadsAlive> = $i1|41"))
                                // System.out.print("");

                                // If the race passes the quick check, we build
                                // constraints
                                // for it and determine if it is race by solving
                                // the constraints

                                StringBuilder sb;
                                if (config.allconsistent)// all read-write
                                                         // consistency used by
                                                         // the Said approach
                                {
                                    List<ReadEvent> readNodes_rw = trace.getAllReadNodes();
                                    sb = engine.constructCausalReadWriteConstraintsOptimized(
                                            rnode.getGID(), readNodes_rw,
                                            trace);
                                } else {

                                    // the following builds the constraints for
                                    // maximal causal model

                                    // get dependent nodes of rnode and wnode
                                    // if w/o branch information, then all read
                                    // nodes that happen-before rnode/wnode are
                                    // considered
                                    // otherwise, only the read nodes that
                                    // before the most recent branch nodes
                                    // before rnode/wnode are considered
                                    List<ReadEvent> readNodes_r = trace.getDependentReadNodes(
                                            rnode, config.branch);
                                    List<ReadEvent> readNodes_w = trace.getDependentReadNodes(
                                            wnode, config.branch);

                                    // construct the optimized read-write
                                    // constraints ensuring the feasibility of
                                    // rnode and wnode
                                    StringBuilder sb1 = engine
                                            .constructCausalReadWriteConstraintsOptimized(
                                                    rnode.getGID(), readNodes_r,
                                                    trace);
                                    StringBuilder sb2 = engine
                                            .constructCausalReadWriteConstraintsOptimized(-1,
                                                    readNodes_w, trace);
                                    // conjunct them
                                    sb = sb1.append(sb2);
                                }

                                // if(race.toString().equals("<benchmarks.raytracer.TournamentBarrier: void DoBarrier(int)>|$z3 = $r2[$i7]|65 - <benchmarks.raytracer.TournamentBarrier: void DoBarrier(int)>|$r3[i0] = z0|76"))
                                // System.out.print("");

                                // query the engine to check rnode/wnode forms a
                                // race or not
                                if (engine.isRace(rnode, wnode, sb)) {
                                    // real race found

                                    logger.report(race.toString(), Logger.MSGTYPE.REAL);// report
                                                                                        // it
                                    if (config.allrace)
                                        violations.add(race2);// save it to
                                                              // violations
                                    else
                                        violations.add(race);

                                    if (equiMap.containsKey(rnode) || equiMap.containsKey(wnode)) {
                                        HashSet<MemoryAccessEvent> nodes1 = new HashSet<MemoryAccessEvent>();
                                        nodes1.add(rnode);
                                        if (equiMap.get(rnode) != null)
                                            nodes1.addAll(equiMap.get(rnode));
                                        HashSet<MemoryAccessEvent> nodes2 = new HashSet<MemoryAccessEvent>();
                                        nodes2.add(wnode);
                                        if (equiMap.get(wnode) != null)
                                            nodes2.addAll(equiMap.get(wnode));

                                        for (Iterator<MemoryAccessEvent> nodesIt1 = nodes1.iterator(); nodesIt1
                                                .hasNext();) {
                                            MemoryAccessEvent node1 = nodesIt1.next();
                                            for (Iterator<MemoryAccessEvent> nodesIt2 = nodes2.iterator(); nodesIt2
                                                    .hasNext();) {
                                                MemoryAccessEvent node2 = nodesIt2.next();
                                                Race r = new Race(trace.getStmtSigIdMap().get(
                                                        node1.getID()), trace.getStmtSigIdMap()
                                                        .get(node2.getID()), node1.getID(),
                                                        node2.getID());
                                                if (violations.add(r))
                                                    logger.report(r.toString(), Logger.MSGTYPE.REAL);

                                            }
                                        }
                                    }
                                } else {
                                    // report potential races

                                    // if we arrive here, it means we find a
                                    // case where
                                    // lockset+happens-before could produce
                                    // false positive
                                    if (potentialviolations.add(race2))
                                        logger.report("Potential " + race2,
                                                Logger.MSGTYPE.POTENTIAL);

                                    if (equiMap.containsKey(rnode) || equiMap.containsKey(wnode)) {
                                        HashSet<MemoryAccessEvent> nodes1 = new HashSet<MemoryAccessEvent>();
                                        nodes1.add(rnode);
                                        if (equiMap.get(rnode) != null)
                                            nodes1.addAll(equiMap.get(rnode));
                                        HashSet<MemoryAccessEvent> nodes2 = new HashSet<MemoryAccessEvent>();
                                        nodes2.add(wnode);
                                        if (equiMap.get(wnode) != null)
                                            nodes2.addAll(equiMap.get(wnode));

                                        for (Iterator<MemoryAccessEvent> nodesIt1 = nodes1.iterator(); nodesIt1
                                                .hasNext();) {
                                            MemoryAccessEvent node1 = nodesIt1.next();
                                            for (Iterator<MemoryAccessEvent> nodesIt2 = nodes2.iterator(); nodesIt2
                                                    .hasNext();) {
                                                MemoryAccessEvent node2 = nodesIt2.next();

                                                ExactRace r = new ExactRace(trace.getStmtSigIdMap()
                                                        .get(node1.getID()), trace
                                                        .getStmtSigIdMap().get(node2.getID()),
                                                        (int) node1.getGID(), (int) node2.getGID());
                                                if (potentialviolations.add(r))
                                                    logger.report("Potential " + r,
                                                            Logger.MSGTYPE.POTENTIAL);

                                            }
                                        }
                                    }

                                }

                            }
                        }
                    }
                }
            // check race write-write
            if (writeEvents.size() > 1)
                for (int i = 0; i < writeEvents.size(); i++)// skip the initial
                                                           // write node
                {
                    WriteEvent wnode1 = writeEvents.get(i);

                    for (int j = 0; j != i && j < writeEvents.size(); j++) {
                        WriteEvent wnode2 = writeEvents.get(j);
                        if (wnode1.getTID() != wnode2.getTID()) {
                            Race race = new Race(trace.getStmtSigIdMap().get(wnode1.getID()), trace
                                    .getStmtSigIdMap().get(wnode2.getID()), wnode1.getID(),
                                    wnode2.getID());
                            ExactRace race2 = new ExactRace(race, (int) wnode1.getGID(),
                                    (int) wnode2.getGID());

                            if (config.allrace || !violations.contains(race)
                                    && !potentialviolations.contains(race2))//
                            {
                                if (engine.hasCommonLock(wnode1, wnode2))
                                    continue;

                                if (wnode1.getGID() < wnode2.getGID()) {
                                    if (engine.canReach(wnode1, wnode2))
                                        continue;
                                } else {
                                    if (engine.canReach(wnode2, wnode1))
                                        continue;
                                }

                                StringBuilder sb;
                                if (config.allconsistent) {
                                    List<ReadEvent> readNodes_ww = trace.getAllReadNodes();
                                    sb = engine.constructCausalReadWriteConstraintsOptimized(-1,
                                            readNodes_ww, trace);
                                } else {
                                    // get dependent nodes of rnode and wnode
                                    List<ReadEvent> readNodes_w1 = trace.getDependentReadNodes(
                                            wnode1, config.branch);
                                    List<ReadEvent> readNodes_w2 = trace.getDependentReadNodes(
                                            wnode2, config.branch);

                                    StringBuilder sb1 = engine
                                            .constructCausalReadWriteConstraintsOptimized(-1,
                                                    readNodes_w1, trace);
                                    StringBuilder sb2 = engine
                                            .constructCausalReadWriteConstraintsOptimized(-1,
                                                    readNodes_w2, trace);
                                    sb = sb1.append(sb2);
                                }
                                // TODO: NEED to ensure that the other
                                // non-dependent nodes by other threads are not
                                // included
                                if (engine.isRace(wnode1, wnode2, sb)) {
                                    logger.report(race.toString(), Logger.MSGTYPE.REAL);

                                    if (config.allrace)
                                        violations.add(race2);// save it to
                                                              // violations
                                    else
                                        violations.add(race);

                                    if (equiMap.containsKey(wnode1) || equiMap.containsKey(wnode2)) {
                                        HashSet<MemoryAccessEvent> nodes1 = new HashSet<MemoryAccessEvent>();
                                        nodes1.add(wnode1);
                                        if (equiMap.get(wnode1) != null)
                                            nodes1.addAll(equiMap.get(wnode1));
                                        HashSet<MemoryAccessEvent> nodes2 = new HashSet<MemoryAccessEvent>();
                                        nodes2.add(wnode2);
                                        if (equiMap.get(wnode2) != null)
                                            nodes2.addAll(equiMap.get(wnode2));

                                        for (Iterator<MemoryAccessEvent> nodesIt1 = nodes1.iterator(); nodesIt1
                                                .hasNext();) {
                                            MemoryAccessEvent node1 = nodesIt1.next();
                                            for (Iterator<MemoryAccessEvent> nodesIt2 = nodes2.iterator(); nodesIt2
                                                    .hasNext();) {
                                                MemoryAccessEvent node2 = nodesIt2.next();
                                                Race r = new Race(trace.getStmtSigIdMap().get(
                                                        node1.getID()), trace.getStmtSigIdMap()
                                                        .get(node2.getID()), node1.getID(),
                                                        node2.getID());
                                                if (violations.add(r))
                                                    logger.report(r.toString(), Logger.MSGTYPE.REAL);

                                            }
                                        }
                                    }
                                } else {
                                    // if we arrive here, it means we find a
                                    // case where lockset+happens-before could
                                    // produce false positive
                                    if (potentialviolations.add(race2))
                                        logger.report("Potential " + race2,
                                                Logger.MSGTYPE.POTENTIAL);

                                    if (equiMap.containsKey(wnode1) || equiMap.containsKey(wnode2)) {
                                        HashSet<MemoryAccessEvent> nodes1 = new HashSet<MemoryAccessEvent>();
                                        nodes1.add(wnode1);
                                        if (equiMap.get(wnode1) != null)
                                            nodes1.addAll(equiMap.get(wnode1));
                                        HashSet<MemoryAccessEvent> nodes2 = new HashSet<MemoryAccessEvent>();
                                        nodes2.add(wnode2);
                                        if (equiMap.get(wnode2) != null)
                                            nodes2.addAll(equiMap.get(wnode2));

                                        for (Iterator<MemoryAccessEvent> nodesIt1 = nodes1.iterator(); nodesIt1
                                                .hasNext();) {
                                            MemoryAccessEvent node1 = nodesIt1.next();
                                            for (Iterator<MemoryAccessEvent> nodesIt2 = nodes2.iterator(); nodesIt2
                                                    .hasNext();) {
                                                MemoryAccessEvent node2 = nodesIt2.next();
                                                ExactRace r = new ExactRace(trace.getStmtSigIdMap()
                                                        .get(node1.getID()), trace
                                                        .getStmtSigIdMap().get(node2.getID()),
                                                        (int) node1.getGID(), (int) node2.getGID());
                                                if (potentialviolations.add(r))
                                                    logger.report("Potential " + r,
                                                            Logger.MSGTYPE.POTENTIAL);

                                            }
                                        }
                                    }

                                }
                            }
                        }
                    }
                }
        }
    }

    /**
     * The input is the application name and the optional options
     *
     * @param args
     */
    public static void main(String[] args) {
        Configuration config = new Configuration();
        config.parseArguments(args, true);
        config.outdir = "./log";
        NewRVPredict predictor = new NewRVPredict();
        predictor.initPredict(config);
        predictor.addHooks();
        predictor.run();
    }

    public void run() {
        EngineSMTLIB1 engine = new EngineSMTLIB1(config);
        Map<String, Long> initValues = new HashMap<>();

        // process the trace window by window
        for (int round = 0; round * config.window_size < totalTraceLength; round++) {
            long index_start = round * config.window_size + 1;
            long index_end = (round + 1) * config.window_size;
            // if(totalTraceLength>rvpredict.config.window_size)System.out.println("***************** Round "+(round+1)+": "+index_start+"-"+index_end+"/"+totalTraceLength+" ******************\n");

            // load trace
            Trace trace = dbEngine.getTrace(index_start, index_end, initValues, traceInfo);

            // OPT: if #sv==0 or #shared rw ==0 continue
            if (trace.mayRace()) {
                // Now, construct the constraints

                // 1. declare all variables
                engine.declareVariables(trace.getFullTrace());
                // 2. intra-thread order for all nodes, excluding branches
                // and basic block transitions
                if (config.rmm_pso)// TODO: add intra order between sync
                    engine.addPSOIntraThreadConstraints(trace.getMemAccessEventsTable());
                else
                    engine.addIntraThreadConstraints(trace.getThreadIdToEventsMap());

                // 3. order for locks, signals, fork/joins
                engine.addSynchronizationConstraints(trace, trace.getSyncNodesMap(),
                        trace.getThreadFirstNodeMap(), trace.getThreadLastNodeMap());

                // 4. match read-write
                // This is only used for constructing all read-write
                // consistency constraints

                // engine.addReadWriteConstraints(trace.getIndexedReadNodes(),trace.getIndexedWriteNodes());
                // engine.addReadWriteConstraints(trace.getIndexedReadNodes(),trace.getIndexedWriteNodes());

                detectRace(engine, trace);
            }

            /* use the final values of the current window as the initial values
             * of the next window */
            initValues = trace.getFinalValues();
        }
        System.exit(0);
    }

    public void initPredict(Configuration conf) {
        config = conf;
        logger = config.logger;

        // Now let's start predict analysis
        startTime = System.currentTimeMillis();

        // db engine is used for interacting with database
        dbEngine = new DBEngine(config.outdir);

        // load all the metadata in the application
        dbEngine.getMetadata(threadIdNameMap, sharedVarIdSigMap, volatileFieldIds, stmtIdSigMap);

        // the total number of events in the trace
        totalTraceLength = 0;
        totalTraceLength = dbEngine.getTraceSize();

        traceInfo = new TraceInfo(sharedVarIdSigMap, volatileFieldIds, stmtIdSigMap,
                threadIdNameMap);
    }

    public void addHooks() {
        ExecutionInfoTask task = new ExecutionInfoTask(startTime, traceInfo, totalTraceLength);
        // register a shutdown hook to store runtime statistics
        Runtime.getRuntime().addShutdownHook(task);

        // set a timer to timeout in a configured period
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                logger.report("\n******* Timeout " + config.timeout + " seconds ******",
                        Logger.MSGTYPE.REAL);// report it
                System.exit(0);
            }
        }, config.timeout * 1000);
    }

    class ExecutionInfoTask extends Thread {
        TraceInfo info;
        long start_time;
        long TOTAL_TRACE_LENGTH;

        ExecutionInfoTask(long st, TraceInfo info, long size) {
            this.info = info;
            this.start_time = st;
            this.TOTAL_TRACE_LENGTH = size;
        }

        @Override
        public void run() {

            // Report statistics about the trace and race detection

            // TODO: query the following information from DB may be expensive

            int TOTAL_THREAD_NUMBER = info.getTraceThreadNumber();
            int TOTAL_SHAREDVARIABLE_NUMBER = info.getTraceSharedVariableNumber();
            int TOTAL_BRANCH_NUMBER = info.getTraceBranchNumber();
            int TOTAL_SHAREDREADWRITE_NUMBER = info.getTraceSharedReadWriteNumber();
            int TOTAL_LOCALREADWRITE_NUMBER = info.getTraceLocalReadWriteNumber();
            int TOTAL_INITWRITE_NUMBER = info.getTraceInitWriteNumber();

            int TOTAL_SYNC_NUMBER = info.getTraceSyncNumber();
            int TOTAL_PROPERTY_NUMBER = info.getTracePropertyNumber();

            if (violations.size() == 0)
                logger.report("No races found.", Logger.MSGTYPE.INFO);
            else {
                logger.report("Trace Size: " + TOTAL_TRACE_LENGTH, Logger.MSGTYPE.STATISTICS);
                logger.report("Total #Threads: " + TOTAL_THREAD_NUMBER, Logger.MSGTYPE.STATISTICS);
                logger.report("Total #SharedVariables: " + TOTAL_SHAREDVARIABLE_NUMBER,
                        Logger.MSGTYPE.STATISTICS);
                logger.report("Total #Shared Read-Writes: " + TOTAL_SHAREDREADWRITE_NUMBER,
                        Logger.MSGTYPE.STATISTICS);
                logger.report("Total #Local Read-Writes: " + TOTAL_LOCALREADWRITE_NUMBER,
                        Logger.MSGTYPE.STATISTICS);
                logger.report("Total #Initial Writes: " + TOTAL_INITWRITE_NUMBER,
                        Logger.MSGTYPE.STATISTICS);
                logger.report("Total #Synchronizations: " + TOTAL_SYNC_NUMBER,
                        Logger.MSGTYPE.STATISTICS);
                logger.report("Total #Branches: " + TOTAL_BRANCH_NUMBER, Logger.MSGTYPE.STATISTICS);
                logger.report("Total #Property Events: " + TOTAL_PROPERTY_NUMBER,
                        Logger.MSGTYPE.STATISTICS);

                logger.report("Total #Potential Violations: "
                        + (potentialviolations.size() + violations.size()),
                        Logger.MSGTYPE.STATISTICS);
                logger.report("Total #Real Violations: " + violations.size(),
                        Logger.MSGTYPE.STATISTICS);
                logger.report("Total Time: " + (System.currentTimeMillis() - start_time) + "ms",
                        Logger.MSGTYPE.STATISTICS);
            }

            logger.closePrinter();

        }

    }

}
