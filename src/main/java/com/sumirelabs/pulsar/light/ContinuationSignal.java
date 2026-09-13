package com.sumirelabs.pulsar.light;

import java.util.concurrent.atomic.AtomicInteger;

/** Coalesced wake-up for one worker; requests during a callback survive for its next turn. */
final class ContinuationSignal {
    private static final int IDLE=0,REQUESTED=1,CLOSED=2;
    private final AtomicInteger state=new AtomicInteger();
    private final Runnable wake;
    ContinuationSignal(Runnable wake) {this.wake=java.util.Objects.requireNonNull(wake);}
    void request() {if(state.compareAndSet(IDLE,REQUESTED))wake.run();}
    boolean pending() {return state.get()==REQUESTED;}
    void runOne(Runnable action) {if(state.compareAndSet(REQUESTED,IDLE))action.run();}
    void close() {state.set(CLOSED);}
}
