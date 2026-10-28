from pathlib import Path
import difflib
ROOT=Path(__file__).resolve().parent
BASE=ROOT/'tools/lightbench'
p=BASE/'src/main/java/com/sumirelabs/lightbench/LightProbe.java'
old=p.read_text(encoding='utf-8')
new=old.replace('private final Method pulsarAwaitPendingWork;', 'private final Method pulsarAwaitPendingWork;\n    private final Method pulsarPublishSamples;')
new=new.replace('this.pulsarAwaitPendingWork = pulsarAwaitPendingWork;', '''this.pulsarAwaitPendingWork = pulsarAwaitPendingWork;
        Method publish = null;
        if (pulsarManager != null) try { publish = pulsarManager.getClass().getMethod("publishContextualLight"); }
        catch (NoSuchMethodException ignored) {}
        this.pulsarPublishSamples = publish;''')
new=new.replace('return this.pulsarAwaitPendingWork == null\n', 'if (this.pulsarPublishSamples != null) return "pulsar_owner_samples_then_global_completion_e2ee1";\n                return this.pulsarAwaitPendingWork == null\n')
new=new.replace('case PULSAR:\n                if (affectedPosition != null', 'case PULSAR:\n                if (this.pulsarPublishSamples != null) this.pulsarPublishSamples.invoke(this.pulsarManager);\n                if (affectedPosition != null')
new=new.replace('while (this.pulsarHasUpdates.read()) {', '''while (true) {
                    if (!this.pulsarHasUpdates.read()) {
                        if (this.pulsarPublishSamples == null) break;
                        this.pulsarPublishSamples.invoke(this.pulsarManager);
                        if (!this.pulsarHasUpdates.read()) break;
                    }''')
assert new!=old and new.count('private final Method pulsarPublishSamples;')==1
p.write_text(new,encoding='utf-8')
(ROOT/'lightbench-completion.patch').write_text(''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='LightProbe.java',tofile='LightProbe.java')),encoding='utf-8')
p=BASE/'gradle.properties';s=p.read_text(encoding='utf-8');s=s.replace('mod_version = 1.1.0-bench9','mod_version = 1.1.0-bench9-e2ee1');p.write_text(s,encoding='utf-8')
p=BASE/'src/main/java/com/sumirelabs/lightbench/Lightbench.java';s=p.read_text(encoding='utf-8')
s=s.replace('event.registerServerCommand(new CommandLightbench());','event.registerServerCommand(new CommandLightbench());\n        event.registerServerCommand(new BenchMetrics());')
s=s.replace('TpsTest.onTickStart();','BenchMetrics.tickStart();\n                TpsTest.onTickStart();')
s=s.replace('SpikeTest.onTickEnd();','SpikeTest.onTickEnd();\n                BenchMetrics.tickEnd(event.getServer());')
# ServerTickEvent in 1.12.2 has no getServer(); resolve the owning server explicitly.
s=s.replace('BenchMetrics.tickEnd(event.getServer());','BenchMetrics.tickEnd(net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance());')
s=s.replace('public static class TickHandler {','''public static class TickHandler {
        @SubscribeEvent public static void onWorldLoad(net.minecraftforge.event.world.WorldEvent.Load event) {
            if (!event.getWorld().isRemote) BenchMetrics.prepareWorld(event.getWorld());
        }''')
p.write_text(s,encoding='utf-8')
print('HARNESS_PATCHED owner-thread publication included in completion wall time')
