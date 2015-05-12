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

import com.runtimeverification.rvpredict.log.Event;
import com.runtimeverification.rvpredict.smt.formula.*;
import com.runtimeverification.rvpredict.trace.LockRegion;
import com.runtimeverification.rvpredict.trace.Trace;

import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.runtimeverification.rvpredict.config.Configuration;
import com.runtimeverification.rvpredict.util.Constants;

public class SMTConstraintBuilder {

    private final Trace trace;

    /**
     * Maps each event to its group ID in the contracted MHB graph.
     * <p>
     * The must-happens-before (MHB) relations form a special DAG where only a
     * few nodes have more than one outgoing edge. To speed up the reachability
     * query between two nodes, we first collapsed the DAG as much as possible.
     */
    private final int[] groupId;

    /**
     * Keeps track of the must-happens-before (MHB) relations in paper.
     */
    private final TransitiveClosure closure = new TransitiveClosure();

    private final LockSetEngine lockEngine = new LockSetEngine();

    private final Solver solver;

    private final Map<Event, Formula> abstractPhi = Maps.newHashMap();
    private final Map<Event, Formula> concretePhi = Maps.newHashMap();

    /**
     * Avoids infinite recursion when building the abstract feasibility
     * constraint of a {@link MemoryAccessEvent}.
     */
    private final Set<Event> computedAbstractPhi = Sets.newHashSet();

    /**
     * Avoids infinite recursion when building the concrete feasibility
     * constraint of a {@link MemoryAccessEvent}.
     */
    private final Set<Event> computedConcretePhi = Sets.newHashSet();

    // constraints below
    private final FormulaTerm.Builder smtlibAssertionBuilder = FormulaTerm.andBuilder();

    public SMTConstraintBuilder(Configuration config, Trace trace) {
        this.trace = trace;
        this.groupId = new int[trace.getSize()];
        this.solver = new Z3Wrapper(config);
    }

    private FormulaTerm getAsstHappensBefore(Event event1, Event event2) {
        return FormulaTerm.LESS_THAN(new OrderVariable(event1), new OrderVariable(event2));
    }

    private FormulaTerm getAsstLockRegionHappensBefore(LockRegion lockRegion1, LockRegion lockRegion2) {
        Event unlock = lockRegion1.getUnlock();
        Event lock = lockRegion2.getLock();
        return getAsstHappensBefore(
                unlock != null ? unlock : trace.getLastEvent(lockRegion1.getTID()),
                lock != null ? lock : trace.getFirstEvent(lockRegion2.getTID()));
    }

    private void assertMutualExclusion(LockRegion lockRegion1, LockRegion lockRegion2) {
        smtlibAssertionBuilder.add(FormulaTerm.OR(
                getAsstLockRegionHappensBefore(lockRegion1, lockRegion2),
                getAsstLockRegionHappensBefore(lockRegion2, lockRegion1)));
    }

    private int getRelativeIdx(Event event) {
        return (int) (event.getGID() - trace.getBaseGID());
    }

    private int getGroupId(Event e) {
        return groupId[getRelativeIdx(e)];
    }

    private void setGroupId(Event e, int id) {
        groupId[getRelativeIdx(e)] = id;
    }

    /**
     * Adds program order constraints.
     */
    public void addIntraThreadConstraints() {
        for (List<Event> events : trace.perThreadView()) {
            setGroupId(events.get(0), closure.nextElemId());
            for (int i = 1; i < events.size(); i++) {
                Event e1 = events.get(i - 1);
                Event e2 = events.get(i);
                smtlibAssertionBuilder.add(getAsstHappensBefore(e1, e2));
                /* every group should start with a join event and end with a start event */
                if (e1.isStart() || e2.isJoin()) {
                    setGroupId(e2, closure.nextElemId());
                    closure.addRelation(getGroupId(e1), getGroupId(e2));
                } else {
                    setGroupId(e2, getGroupId(e1));
                }
            }
        }
    }

    /**
     * Adds thread start/join constraints.
     */
    public void addThreadStartJoinConstraints() {
        Iterables.concat(trace.perThreadView()).forEach(event -> {
            if (event.isStart()) {
                Event fst = trace.getFirstEvent(event.getSyncObject());
                if (fst != null) {
                    smtlibAssertionBuilder.add(getAsstHappensBefore(event, fst));
                    closure.addRelation(getGroupId(event), getGroupId(fst));
                }
            } else if (event.isJoin()) {
                Event last = trace.getLastEvent(event.getSyncObject());
                if (last != null) {
                    smtlibAssertionBuilder.add(getAsstHappensBefore(last, event));
                    closure.addRelation(getGroupId(last), getGroupId(event));
                }
            }
        });
    }

    /**
     * Adds lock mutual exclusion constraints.
     */
    public void addLockingConstraints() {
        trace.getLockIdToLockRegions().forEach((lockId, lockRegions) -> {
            lockEngine.addAll(lockRegions);

            /* assert lock regions mutual exclusion */
            lockRegions.forEach(lr1 -> {
                lockRegions.forEach(lr2 -> {
                    if (lr1.getTID() < lr2.getTID()
                            && (lr1.isWriteLocked() || lr2.isWriteLocked())) {
                        assertMutualExclusion(lr1, lr2);
                    }
                });
            });
        });
    }

    /**
     * Generates a formula ensuring that all read events that {@code event}
     * depends on read the same value as in the original trace, to guarantee
     * {@code event} will be generated in the predicted trace. Note that,
     * however, this {@code event} is allowed to read or write a different value
     * than in the original trace.
     */
    public Formula getPhiAbs(Event event) {
        if (computedAbstractPhi.contains(event)) {
            return new AbstractPhiVariable(event);
        }
        computedAbstractPhi.add(event);

        FormulaTerm.Builder phiBuilder = FormulaTerm.andBuilder();
        /* make sure that every dependent read event reads the same value as in the original trace */
        for (Event depRead : trace.getCtrlFlowDependentEvents(event)) {
            phiBuilder.add(getPhiConc(depRead));
        }
        abstractPhi.put(event, phiBuilder.build());
        return new AbstractPhiVariable(event);
    }

    /**
     * Generates a formula ensuring that {@code event} will be generated exactly
     * the same as in the original trace <b>if</b> the corresponding data-abstract
     * feasibility constraint is also satisfied.
     */
    private Formula getPhiConc(Event event) {
        if (computedConcretePhi.contains(event)) {
            return new ConcretePhiVariable(event);
        } else if (event.getValue() == Constants._0X_DEADBEEFL) {
            return BooleanConstant.TRUE;
        }
        computedConcretePhi.add(event);

        Formula phi;
        if (event.isRead()) {
            List<Event> writeEvents = trace.getWriteEvents(event.getAddr());

            // all write events that could interfere with the read event
            List<Event> predWrites = Lists.newArrayList();
            Event sameThreadPredWrite = null;
            for (Event write : writeEvents) {
                if (write.getTID() == event.getTID()) {
                    if (write.getGID() < event.getGID()) {
                        sameThreadPredWrite = write;
                    }
                } else if (!happensBefore(event, write)) {
                    predWrites.add(write);
                }
            }
            if (sameThreadPredWrite != null) {
                predWrites.add(sameThreadPredWrite);
            }

            // all write events whose values could be read by the read event
            List<Event> sameValPredWrites = predWrites.stream()
                    .filter(w -> w.getValue() == event.getValue()).collect(Collectors.toList());

            /* case 1: the read event reads the initial value */
            Formula case1 = BooleanConstant.FALSE;
            if (sameThreadPredWrite == null &&
                    trace.getInitValueOf(event.getAddr()) == event.getValue()) {
                FormulaTerm.Builder builder = FormulaTerm.andBuilder();
                predWrites.forEach(w -> builder.add(getAsstHappensBefore(event, w)));
                case1 = builder.build();
            }

            /* case 2: the read event reads a previously written value */
            FormulaTerm.Builder case2Builder = FormulaTerm.orBuilder();
            sameValPredWrites.forEach(w1 -> {
                FormulaTerm.Builder builder = FormulaTerm.andBuilder();
                builder.add(getPhiAbs(w1), getPhiConc(w1));
                builder.add(getAsstHappensBefore(w1, event));
                predWrites.forEach(w2 -> {
                    if (w2.getValue() != w1.getValue() && !happensBefore(w2, w1)) {
                        builder.add(FormulaTerm.OR(getAsstHappensBefore(w2, w1),
                                getAsstHappensBefore(event, w2)));
                    }
                });
                case2Builder.add(builder.build());
            });
            phi = FormulaTerm.OR(case1, case2Builder.build());
        } else {
            phi = BooleanConstant.TRUE;
        }
        concretePhi.put(event, phi);

        return new ConcretePhiVariable(event);
    }

    /**
     * Checks if two {@code MemoryAccessEvent} hold a common lock.
     */
    public boolean hasCommonLock(Event e1, Event e2) {
        return lockEngine.hasCommonLock(e1, e2);
    }

    /**
     * Checks if one event happens before another.
     */
    public boolean happensBefore(Event e1, Event e2) {
        return closure.inRelation(getGroupId(e1), getGroupId(e2));
    }

    public boolean isRace(Event e1, Event e2) {
        FormulaTerm.Builder raceAsstBuilder = FormulaTerm.andBuilder();
        raceAsstBuilder.add(smtlibAssertionBuilder.build());
        raceAsstBuilder.add(getPhiAbs(e1));
        raceAsstBuilder.add(getPhiAbs(e2));
        abstractPhi.forEach((e, phi) -> {
            raceAsstBuilder.add(FormulaTerm.BOOL_EQUAL(new AbstractPhiVariable(e), phi));
        });
        concretePhi.forEach((e, phi) -> {
            raceAsstBuilder.add(FormulaTerm.BOOL_EQUAL(new ConcretePhiVariable(e), phi));
        });
        raceAsstBuilder.add(FormulaTerm
                .INT_EQUAL(new OrderVariable(e1), new OrderVariable(e2)));

        return solver.isSat(raceAsstBuilder.build());
    }

    public void finish() {
        closure.finish();
    }

}
