from pathlib import Path
import json,subprocess
R=Path(__file__).resolve().parents[1];O=Path('E:/Github/pulsar-hotpath-attribution-20261012');P=Path('E:/Github/Pulsar')
status=json.loads((R/'progress.json').read_text(encoding='utf-8-sig'));assert status['status']=='measurements-and-audits-complete'
audits=json.loads((R/'analysis/audit-summary.json').read_text(encoding='utf-8'));assert len(audits)==3 and all(v['valid'] for v in audits.values())
assert not subprocess.check_output(['git','status','--porcelain'],cwd=P).strip(),'Main has user changes; preserve them'
assert subprocess.check_output(['git','rev-parse','--short','HEAD'],cwd=P).decode().strip()=='d9b357b'
dst=P/'src/main/java/com/sumirelabs/pulsar/light/engine'
(dst/'ScalarSkyEngine.java').write_bytes((O/'sources/ScalarSkyEngine-pre-air-fast.java').read_bytes().replace(b'\r\n',b'\n'))
(dst/'ScalarBlockEngine.java').write_bytes((O/'repo/src/main/java/com/sumirelabs/pulsar/light/engine/ScalarBlockEngine.java').read_bytes().replace(b'\r\n',b'\n'))
p=P/'src/main/java/com/sumirelabs/pulsar/light/ParallelLightScheduler.java';t=p.read_text(encoding='utf-8')
t=t.replace('    private int turn;','    private int turn;\n    private static final long IDLE_SPIN_NS = 5_000L;')
old='''    private void run() {
        while (true) {''';assert t.count(old)==1
t=t.replace(old,'''    private void run() {
        long warmUntil = 0L;
        while (true) {''')
old='''            if (claim == null) {
                try { work.acquire(); }''';assert t.count(old)==1
t=t.replace(old,'''            if (claim == null) {
                // A consumed hint must be followed by a new claim, never a wait.
                if (work.tryAcquire()) {
                    signalled.set(false);
                    continue;
                }
                // Catch a rapid follow-up on the recent worker without holding
                // the dispatcher or a queue monitor while waiting for its hint.
                if (warmUntil != 0L && System.nanoTime() - warmUntil < 0L) {
                    while (!signalled.get() && System.nanoTime() - warmUntil < 0L) {
                        Thread.onSpinWait();
                    }
                    if (signalled.get()) continue;
                }
                try { work.acquire(); }''')
old='''                signal();
            }
        }
    }

    private static void process''';assert t.count(old)==1
t=t.replace(old,'''                signal();
            }
            warmUntil = System.nanoTime() + IDLE_SPIN_NS;
        }
    }

    private static void process''')
p.write_bytes(t.encode())
for relative in ['engine/SourceFaceDirectionFilterTest.java','ParallelLightSchedulerWakeRaceTest.java']:
 src=O/'repo/src/test/java/com/sumirelabs/pulsar/light'/relative;out=P/'src/test/java/com/sumirelabs/pulsar/light'/relative;out.write_bytes(src.read_bytes().replace(b'\r\n',b'\n'))
print('Adopted Sky prefilter, Block plain-air paths, bounded recent-worker hint wait; no diagnostic helpers or rejected cache/coordinate changes')
