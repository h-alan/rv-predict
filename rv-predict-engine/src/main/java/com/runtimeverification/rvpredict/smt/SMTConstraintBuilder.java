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
package com.runtimeverification.rvpredict.smt;

import com.runtimeverification.rvpredict.smt.formula.*;
import com.runtimeverification.rvpredict.smt.visitors.SMTLib1Filter;
import com.runtimeverification.rvpredict.trace.Event;
import com.runtimeverification.rvpredict.trace.EventType;
import com.runtimeverification.rvpredict.trace.MemoryAccessEvent;
import com.runtimeverification.rvpredict.trace.SyncEvent;
import com.runtimeverification.rvpredict.trace.LockRegion;
import com.runtimeverification.rvpredict.trace.ReadEvent;
import com.runtimeverification.rvpredict.trace.Trace;
import com.runtimeverification.rvpredict.trace.WriteEvent;
import com.runtimeverification.rvpredict.graph.LockSetEngine;
import com.runtimeverification.rvpredict.graph.ReachabilityEngine;

import java.util.Map;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import com.runtimeverification.rvpredict.config.Configuration;
import com.runtimeverification.rvpredict.util.Constants;

public class SMTConstraintBuilder {

    private static AtomicInteger id = new AtomicInteger();// constraint id
    private SMTTaskRun task;

    private final Configuration config;

    private final Trace trace;

    private final ReachabilityEngine reachEngine = new ReachabilityEngine();
    private final LockSetEngine lockEngine = new LockSetEngine();

    private final Map<MemoryAccessEvent, FormulaTerm> abstractPhi = Maps.newHashMap();
    private final Map<MemoryAccessEvent, FormulaTerm> concretePhi = Maps.newHashMap();

    /**
     * Avoids infinite recursion when building the abstract feasibility
     * constraint of a {@link MemoryAccessEvent}.
     */
    private final Set<MemoryAccessEvent> computedAbstractPhi = Sets.newHashSet();

    /**
     * Avoids infinite recursion when building the concrete feasibility
     * constraint of a {@link MemoryAccessEvent}.
     */
    private final Set<MemoryAccessEvent> computedConcretePhi = Sets.newHashSet();

    // constraints below
    private final FormulaTerm smtlibAssertion = new FormulaTerm(BooleanOperation.AND);
    private final String benchname;

    public SMTConstraintBuilder(Configuration config, Trace trace) {
        this.config = config;
        this.trace = trace;
        benchname = config.tableName;
    }

    private void assertHappensBefore(Event e1, Event e2) {
        smtlibAssertion.addFormula(getAsstHappensBefore(e1, e2));
        reachEngine.addEdge(e1, e2);
    }

    private FormulaTerm getAsstHappensBefore(Event event1, Event event2) {
        return new FormulaTerm(BooleanOperation.LESS_THAN, new OrderVariable(event1), new OrderVariable(event2));
    }

    private FormulaTerm getAsstLockRegionHappensBefore(LockRegion lockRegion1, LockRegion lockRegion2) {
        SyncEvent unlock = lockRegion1.getUnlock();
        SyncEvent lock = lockRegion2.getLock();
        return getAsstHappensBefore(
                unlock != null ? unlock : trace.getLastThreadEvent(lockRegion1.getThreadId()),
                lock != null ? lock : trace.getFirstThreadEvent(lockRegion2.getThreadId()));
    }

    private void assertMutualExclusion(LockRegion lockRegion1, LockRegion lockRegion2) {
        smtlibAssertion.addFormula(new FormulaTerm(BooleanOperation.OR, 
                getAsstLockRegionHappensBefore(lockRegion1, lockRegion2),
                getAsstLockRegionHappensBefore(lockRegion2, lockRegion1)));
    }

    /**
     * Adds intra-thread must happens-before (MHB) constraints of sequential
     * consistent memory model.
     */
    public void addIntraThreadConstraints() {
        for (List<Event> events : trace.getThreadIdToEventsMap().values()) {
            Event prevEvent = events.get(0);
            for (Event crntEvent : events.subList(1, events.size())) {
                assertHappensBefore(prevEvent, crntEvent);
                prevEvent = crntEvent;
            }
        }
    }

    /**
     * Adds intra-thread must happens-before (MHB) constraints of relaxed PSO
     * memory model.
     */
    public void addPSOIntraThreadConstraints() {
        for (List<MemoryAccessEvent> nodes : trace.getMemAccessEventsTable().values()) {
            MemoryAccessEvent prevEvent = nodes.get(0);
            for (MemoryAccessEvent crntEvent : nodes.subList(1, nodes.size())) {
                assertHappensBefore(prevEvent, crntEvent);
                prevEvent = crntEvent;
            }
        }
    }

    /**
     * Adds program order and thread start/join constraints, that is, the must
     * happens-before constraints (MHB) in the paper.
     */
    public void addProgramOrderAndThreadStartJoinConstraints() {
        for (List<SyncEvent> startOrJoinEvents : trace.getThreadIdToStartJoinEvents().values()) {
            for (SyncEvent startOrJoin : startOrJoinEvents) {
                long tid = startOrJoin.getSyncObject();
                switch (startOrJoin.getType()) {
                case START:
                    Event fstThrdEvent = trace.getFirstThreadEvent(tid);
                    /* YilongL: it's possible that the first event of the new
                     * thread is not in the current trace */
                    if (fstThrdEvent != null) {
                        assertHappensBefore(startOrJoin, fstThrdEvent);
                    }
                    break;
                case JOIN:
                    if (startOrJoin.getType() == EventType.JOIN) {
                        Event lastThrdEvent = trace.getLastThreadEvent(tid);
                        /* YilongL: it's possible that the last event of the thread
                         * to join is not in the current trace */
                        if (lastThrdEvent != null) {
                            assertHappensBefore(lastThrdEvent, startOrJoin);
                        }
                    }
                    break;
                case PRE_JOIN:
                case JOIN_MAYBE_FAILED:
                    break;
                default:
                    assert false : "unexpected event: " + startOrJoin;
                }
            }
        }
    }

    /**
     * Adds lock mutual exclusion constraints.
     */
    public void addLockingConstraints() {
        /* enumerate the locking events on each lock */
        for (List<SyncEvent> syncEvents : trace.getLockObjToSyncEvents().values()) {
            Map<Long, SyncEvent> threadIdToPrevLockOrUnlock = Maps.newHashMap();
            List<LockRegion> lockRegions = Lists.newArrayList();

            for (SyncEvent syncEvent : syncEvents) {
                long tid = syncEvent.getTID();
                SyncEvent prevLockOrUnlock = threadIdToPrevLockOrUnlock.get(tid);
                assert prevLockOrUnlock == null
                    || !(prevLockOrUnlock.isLockEvent() && syncEvent.isLockEvent())
                    || !(prevLockOrUnlock.isUnlockEvent() && syncEvent.isUnlockEvent()) :
                    "Unexpected consecutive lock/unlock events:\n" + prevLockOrUnlock + ", " + syncEvent;

                switch (syncEvent.getType()) {
                case WRITE_LOCK:
                case READ_LOCK:
                case WAIT_ACQ:
                    threadIdToPrevLockOrUnlock.put(tid, syncEvent);
                    break;

                case WRITE_UNLOCK:
                case READ_UNLOCK:
                case WAIT_REL:
                    lockRegions.add(new LockRegion(threadIdToPrevLockOrUnlock.put(tid, syncEvent),
                            syncEvent));
                    break;
                default:
                    assert false : "Unexpected synchronization event: " + syncEvent;
                }
            }

            for (SyncEvent lockOrUnlock : threadIdToPrevLockOrUnlock.values()) {
                if (lockOrUnlock.isLockEvent()) {
                    SyncEvent lock = lockOrUnlock;
                    lockRegions.add(new LockRegion(lock, null));
                }
            }

            lockEngine.addAll(lockRegions);

            /* assert lock regions mutual exclusion */
            assertLockMutex(lockRegions);
        }
    }

    private void assertLockMutex(List<LockRegion> lockRegions) {
        for (LockRegion lockRegion1 : lockRegions) {
            for (LockRegion lockRegion2 : lockRegions) {
                if (lockRegion1.getThreadId() < lockRegion2.getThreadId()
                        && (lockRegion1.isWriteLocked() || lockRegion2.isWriteLocked())) {
                    assertMutualExclusion(lockRegion1, lockRegion2);
                }
            }
        }
    }

    /**
     * Generates a formula ensuring that all read events that {@code event}
     * depends on read the same value as in the original trace, to guarantee
     * {@code event} will be generated in the predicted trace. Note that,
     * however, this {@code event} is allowed to read or write a different value
     * than in the original trace.
     */
    public Formula getAbstractFeasibilityConstraint(MemoryAccessEvent event) {
        if (computedAbstractPhi.contains(event)) {
            return new AbstractPhiVariable(event);
        }
        computedAbstractPhi.add(event);

        FormulaTerm phi = new FormulaTerm(BooleanOperation.AND);
        /* make sure that every dependent read event reads the same value as in the original trace */
        for (ReadEvent depRead : trace.getCtrlFlowDependentEvents(event)) {
            phi.addFormula(getConcreteFeasibilityConstraint(depRead));
        }
        abstractPhi.put(event, phi);
        return new AbstractPhiVariable(event);
    }

    /**
     * Generates a formula ensuring that {@code event} will be generated exactly
     * the same as in the original trace <b>if</b> the corresponding data-abstract
     * feasibility constraint is also satisfied.
     */
    private Formula getConcreteFeasibilityConstraint(MemoryAccessEvent event) {
        if (computedConcretePhi.contains(event)) {
            return new ConcretePhiVariable(event);
        } else if (event.getValue() == Constants._0X_DEADBEEFL) {
            return BooleanConstant.TRUE;
        }
        computedConcretePhi.add(event);

        FormulaTerm phi;
        if (event instanceof ReadEvent) {
            List<WriteEvent> writeEvents = trace.getWriteEventsOn(event.getAddr());

            /* thread immediate write predecessor */
            WriteEvent thrdImdWrtPred = null;
            /* predecessor write set: all write events whose values could be read by `depRead' */
            List<WriteEvent> predWriteSet = Lists.newArrayList();
            for (WriteEvent write : writeEvents) {
                if (write.getTID() == event.getTID()) {
                    if (write.getGID() < event.getGID()) {
                        thrdImdWrtPred = write;
                    }
                } else if (!happensBefore(event, write)) {
                    predWriteSet.add(write);
                }
            }
            if (thrdImdWrtPred != null) {
                predWriteSet.add(thrdImdWrtPred);
            }

            /* predecessor write set of same value */
            List<WriteEvent> sameValPredWriteSet = Lists.newArrayList();
            for (WriteEvent write : predWriteSet) {
                if (write.getValue() == event.getValue()) {
                    sameValPredWriteSet.add(write);
                }
            }

            /* case 1: the dependent read reads the initial value */
            Formula case1 = BooleanConstant.FALSE;
            if (thrdImdWrtPred == null &&
                    trace.getInitValueOf(event.getAddr()) == event.getValue()) {
                FormulaTerm formula = new FormulaTerm(BooleanOperation.AND);
                for (WriteEvent write : predWriteSet) {
                    formula.addFormula(getAsstHappensBefore(event, write));
                }
                case1 = formula;
            }

            /* case 2: the dependent read reads a previously written value */
            FormulaTerm case2 = new FormulaTerm(BooleanOperation.OR);
            for (WriteEvent write : sameValPredWriteSet) {
                FormulaTerm formula = new FormulaTerm(BooleanOperation.AND,
                        getAbstractFeasibilityConstraint(write),
                        getConcreteFeasibilityConstraint(write));
                case2.addFormula(formula);
                formula.addFormula(getAsstHappensBefore(write, event));
                for (WriteEvent otherWrite : writeEvents) {
                    if (write != otherWrite && !happensBefore(otherWrite, write)
                            && !happensBefore(event, otherWrite)) {
                        formula.addFormula(new FormulaTerm(BooleanOperation.OR,
                                getAsstHappensBefore(otherWrite, write),
                                getAsstHappensBefore(event, otherWrite)));
                    }
                }
            }
            phi = new FormulaTerm(BooleanOperation.OR, case1, case2);
        } else {
            phi = new FormulaTerm(BooleanOperation.AND);
            for (ReadEvent e : trace.getExtraDataFlowDependentEvents(event)) {
                phi.addFormula(getAbstractFeasibilityConstraint(e));
                phi.addFormula(getConcreteFeasibilityConstraint(e));
            }
        }
        concretePhi.put(event, phi);

        return new ConcretePhiVariable(event);
    }

    /**
     * Checks if two {@code MemoryAccessEvent} hold a common lock.
     */
    public boolean hasCommonLock(MemoryAccessEvent e1, MemoryAccessEvent e2) {
        return lockEngine.hasCommonLock(e1, e2);
    }

    /**
     * Checks if one event happens before another.
     */
    public boolean happensBefore(Event e1, Event e2) {
        return reachEngine.canReach(e1.getGID(), e2.getGID());

    }

    public boolean isSat() {
        int id = SMTConstraintBuilder.id.incrementAndGet();
        task = new SMTTaskRun(config, id);

        Benchmark benchmark = new Benchmark(benchname, smtlibAssertion);
        SMTLib1Filter filter = new SMTLib1Filter();
        benchmark.accept(filter);
        task.sendMessage(filter.getResult());

        return task.sat;
    }

    public boolean isRace(Event e1, Event e2, Formula... casualConstraints) {
        int id = SMTConstraintBuilder.id.incrementAndGet();
        task = new SMTTaskRun(config, id);
        FormulaTerm raceAssertion = smtlibAssertion.shallowCopy();
        Benchmark benchmark = new Benchmark(benchname, raceAssertion);
        for (Entry<MemoryAccessEvent, FormulaTerm> entry : abstractPhi.entrySet()) {
            raceAssertion.addFormula(new FormulaTerm(BooleanOperation.BOOL_EQUAL,
                    new AbstractPhiVariable(entry.getKey()),
                    entry.getValue()));
        }
        for (Entry<MemoryAccessEvent, FormulaTerm> entry : concretePhi.entrySet()) {
            raceAssertion.addFormula(new FormulaTerm(BooleanOperation.BOOL_EQUAL,
                    new ConcretePhiVariable(entry.getKey()),
                    entry.getValue()));
        }
        raceAssertion.addFormula(new FormulaTerm(BooleanOperation.INT_EQUAL, new OrderVariable(e1), new OrderVariable(e2)));
        for (Formula casualConstraint : casualConstraints) {
            raceAssertion.addFormula(casualConstraint);
        }
        
        SMTLib1Filter filter = new SMTLib1Filter();
        benchmark.accept(filter);
        task.sendMessage(filter.getResult());
        return task.sat;
    }

}
