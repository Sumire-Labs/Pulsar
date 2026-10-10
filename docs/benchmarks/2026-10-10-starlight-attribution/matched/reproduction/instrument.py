from pathlib import Path
R=Path(__file__).resolve().parents[1];P=R/'pulsar-profile/src/main/java/com/sumirelabs/pulsar';L=R/'lightbench-profile/src/main/java/com/sumirelabs/lightbench';S=R/'starlight-src'
def edit(path,old,new):
    t=path.read_text(encoding='utf-8');assert t.count(old)==1,(path,old,t.count(old));path.write_text(t.replace(old,new),encoding='utf-8')
def import_probe(path):edit(path,'package '+('com.github.starlight.light;' if path.parent==S else 'com.sumirelabs.pulsar.light.engine;'),('package com.github.starlight.light;' if path.parent==S else 'package com.sumirelabs.pulsar.light.engine;')+'\n\nimport com.sumirelabs.benchprobe.BenchTiming;')
edit(P/'light/ChunkTasks.java','LightStats.enabled ? System.nanoTime() : 0L','(LightStats.enabled || com.sumirelabs.benchprobe.BenchTiming.ENABLED) ? System.nanoTime() : 0L')
edit(P/'light/LightEngineWorker.java','''    void processClaimedTask(ChunkTasks task) {
        PulsarEngine engine = null;''','''    void processClaimedTask(ChunkTasks task) {
        final boolean timingSky=this.operationName.equals("propagateSkyChanges");
        final long timing=com.sumirelabs.benchprobe.BenchTiming.taskStart(timingSky,task.enqueueTimeNs);
        PulsarEngine engine = null;''')
edit(P/'light/LightEngineWorker.java','''            if (engine != null) this.releaseEngine(engine);
            this.queue.completeTask(task);''','''            if (engine != null) this.releaseEngine(engine);
            com.sumirelabs.benchprobe.BenchTiming.taskEnd(timingSky,timing);
            this.queue.completeTask(task);''')
for path in [P/'light/engine/PulsarEngine.java',S/'StarLightEngine.java']:
    import_probe(path);t=path.read_text(encoding='utf-8');start=t.index('    public final void blocksChangedInChunk(');end=t.index('\n    }',start)+6;body=t[start:end]
    brace=body.index('{')+1;body=body[:brace]+'\n        final long ta=BenchTiming.engineStart(!this.isClientSide);\n        long tb=0,tc=0,td=0,te=0;'+body[brace:]
    body=body.replace('        try {','        tb=ta==0?0:System.nanoTime();\n        try {',1)
    needle='            // 2. Block changes' if path.name=='PulsarEngine.java' else '            if (positionCount != 0) {'
    body=body.replace(needle,'            tc=ta==0?0:System.nanoTime();\n'+needle,1)
    body=body.replace('            this.updateVisible();','            td=ta==0?0:System.nanoTime();\n            this.updateVisible();\n            te=ta==0?0:System.nanoTime();',1)
    work='BenchTiming.work(this.skylightPropagator,this.lastPositionsProcessed,this.lastBfsIncreaseTotal,this.lastBfsDecreaseTotal);' if path.name=='PulsarEngine.java' else 'BenchTiming.work(this.skylightPropagator,positionCount,0,0);'
    body=body.replace('            this.destroyCaches();','            this.destroyCaches();\n            if(ta!=0&&te!=0){BenchTiming.engine(this.skylightPropagator,ta,tb,tc,td,te,System.nanoTime());'+work+'}',1)
    path.write_text(t[:start]+body+t[end:],encoding='utf-8')
for path in [P/'light/engine/ScalarBlockEngine.java',P/'light/engine/SkyLightColumnProcessor.java',S/'BlockStarLightEngine.java',S/'SkyStarLightEngine.java']:
    import_probe(path);t=path.read_text(encoding='utf-8');is_port=path.parent==S;sky=path.name.startswith('Sky');helper=path.name=='SkyLightColumnProcessor.java'
    needle='    protected void propagateBlockChanges(' if is_port else ('    void processBlockPositionChanges(' if helper else '    protected void processBlockPositionChanges(')
    start=t.index(needle);end=t.index('\n    }',start)+6;body=t[start:end];brace=body.index('{')+1
    body=body[:brace]+('\n        final long ta=BenchTiming.clock(!this.engine.isClientSide);' if helper else '\n        final long ta=BenchTiming.clock(!this.isClientSide);')+body[brace:]
    call='        engine.performLightDecrease();' if helper else '        this.performLightDecrease();'
    assert body.count(call)==1
    body=body.replace(call,'        final long tb=ta==0?0:System.nanoTime();\n'+call+'\n        if(ta!=0)BenchTiming.propagation('+str(sky).lower()+',ta,tb,System.nanoTime());')
    path.write_text(t[:start]+body+t[end:],encoding='utf-8')
# Port counters are added once per queue drain, not to individual queue iterations.
path=S/'StarLightEngine.java';t=path.read_text(encoding='utf-8')
for method,increase in [('performLightIncrease',True),('performLightDecrease',False)]:
    start=t.index('    protected final void '+method+'(');end=t.index('\n    }',start)+6;body=t[start:end]
    line='        BenchTiming.entries(!this.isClientSide,this.skylightPropagator,'+str(increase).lower()+',queueLength);\n'
    if increase:
        at=body.rfind('\n    }');body=body[:at]+'\n'+line.rstrip()+body[at:]
    else:
        needle='        this.performLightIncrease();';assert body.count(needle)==1,(method,body[-300:])
        body=body.replace(needle,line+needle)
    t=t[:start]+body+t[end:]
path.write_text(t,encoding='utf-8')
# Shared direct bridge is present in every benchmark variant; both disabled builds use identical harness code.
path=L/'UpdateBenchmark.java'
edit(path,'        say(sender, "updates warmup: "','        com.sumirelabs.benchprobe.BenchTiming.prepare();\n        say(sender, "updates warmup: "')
edit(path,'performEdit(world, probe, position, air);\n            verifySkyColumn','performEdit(world, probe, position, air, removeSamples==null?"warmup_sky_remove":"sky_remove", index);\n            verifySkyColumn')
edit(path,'performEdit(world, probe, position, stone);\n            verifySkyColumn','performEdit(world, probe, position, stone, placeSamples==null?"warmup_sky_place":"sky_place", index);\n            verifySkyColumn')
edit(path,'performEdit(world, probe, position, glowstone);','performEdit(world, probe, position, glowstone, placeSamples==null?"warmup_block_place":"block_place", index);')
edit(path,'performEdit(world, probe, position, air);\n            verifyBlockLight','performEdit(world, probe, position, air, removeSamples==null?"warmup_block_remove":"block_remove", index);\n            verifyBlockLight')
edit(path,'        final String startedAtUtc = BenchmarkReport.nowUtc();','        com.sumirelabs.benchprobe.BenchTiming.discardWarmup();\n        final String startedAtUtc = BenchmarkReport.nowUtc();')
edit(path,'    private static TimedEdit performEdit(\n','''    private static TimedEdit performEdit(final World world,final LightProbe probe,final BlockPos position,final IBlockState state) throws Exception {
        return performEdit(world,probe,position,state,null,-1);
    }
    private static TimedEdit performEdit(
''')
edit(path,'final World world, final LightProbe probe, final BlockPos position, final IBlockState state)\n            throws Exception {\n        final long start','final World world, final LightProbe probe, final BlockPos position, final IBlockState state, final String phase, final int sampleIndex)\n            throws Exception {\n        final boolean capture=com.sumirelabs.benchprobe.BenchTiming.begin(phase,sampleIndex);\n        final long start')
edit(path,'        return new TimedEdit(submitted - start, barrierNanos, completed - start);','        if(capture)com.sumirelabs.benchprobe.BenchTiming.end(submitted-start,barrierNanos,completed-start,start);\n        return new TimedEdit(submitted - start, barrierNanos, completed - start);')
edit(path,'            say(sender, "raw result saved: " + output.toAbsolutePath());','            com.sumirelabs.benchprobe.BenchTiming.write(output.resolveSibling("sampled-timing.json"));\n            say(sender, "raw result saved: " + output.toAbsolutePath());')
for repo in ['pulsar-profile','lightbench-profile']:
    path=R/repo/'build.gradle';path.write_text(path.read_text(encoding='utf-8')+"\ndependencies { compileOnly files('../probe-api.jar') }\n",encoding='utf-8')
print('Instrumented isolated sources')
