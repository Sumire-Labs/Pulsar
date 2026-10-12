from pathlib import Path
R=Path(__file__).resolve().parents[1]
t=Path('E:/Github/pulsar-handoff-20261010/src/ParallelLightScheduler.java').read_text(encoding='utf-8')
start=t.index('    static void diagnosticBegin(');end=t.index('    static void diagnosticDrain(',start)
t=t[:start]+'''    static void diagnosticBegin(Object owner) throws InterruptedException {
        ParallelLightScheduler s=shared;
        long deadline=System.nanoTime()+5_000_000_000L;
        synchronized(s) {
            if(s.diagnosticOwner!=null) throw new IllegalStateException("Nested diagnostic owner");
            s.diagnosticOwner=owner;s.diagnosticThread=Thread.currentThread();
        }
        while(true) {
            synchronized(s) { if(s.reservations.activeJobs(owner)==0) return; }
            if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) {
                synchronized(s) { s.diagnosticOwner=null;s.diagnosticThread=null; }
                s.signal();
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Diagnostic hold interrupted");
                throw new IllegalStateException("Diagnostic reservation timeout");
            }
            Thread.onSpinWait();
        }
    }

'''+t[end:]
t=t.replace('    private int turn;','    private int turn;\n    private static final long IDLE_SPIN_NS = Math.max(0L, Math.min(50000L, Long.getLong("pulsar.diag.idleSpinNs", 5000L)));')
old='''    private void run() {
        while (true) {''';assert t.count(old)==1
t=t.replace(old,'''    private void run() {
        long warmUntil = 0L;
        while (true) {''')
old='''            if (claim == null) {
                try { work.acquire(); }''';assert t.count(old)==1
t=t.replace(old,'''            if (claim == null) {
                if (IDLE_SPIN_NS > 0L) {
                    // A consumed signal must be followed by a fresh claim, not a wait.
                    if (work.tryAcquire()) {
                        signalled.set(false);
                        continue;
                    }
                    if (warmUntil != 0L && System.nanoTime() - warmUntil < 0L) {
                        while (!signalled.get() && System.nanoTime() - warmUntil < 0L) {
                            Thread.onSpinWait();
                        }
                        if (signalled.get()) continue;
                    }
                }
                try { work.acquire(); }''')
old='''            execute(claim);
        }
    }''';assert t.count(old)==1
t=t.replace(old,'''            execute(claim);
            if (IDLE_SPIN_NS > 0L) warmUntil = System.nanoTime() + IDLE_SPIN_NS;
        }
    }''')
p=R/'spin-src/ParallelLightScheduler.java';p.parent.mkdir(exist_ok=True);p.write_bytes(t.encode())
print(p)
