from pathlib import Path
R=Path(__file__).resolve().parents[1];p=R/'repo/src/main/java/com/sumirelabs/hotpathaudit/HotpathAudit.java';t=p.read_text(encoding='utf-8')
t=t.replace('            try { run(server.getWorld(0)); }','            try { if(args.length>0 && args[0].equals("inspect")) inspect(server,sender); else run(server.getWorld(0)); }')
needle='    private static void run(WorldServer world) throws Exception {';assert t.count(needle)==1
t=t.replace(needle,'''    private static void inspect(MinecraftServer server,ICommandSender sender) throws Exception {
        server.getCommandManager().executeCommand(sender,"lightbench updates 2000 1000");
        WorldServer world=server.getWorld(0);drain(world);
        IBlockState air=Blocks.AIR.getDefaultState();int info=LightInfo.of(air);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("air_info",info);report.put("air_context_flags",info&LightInfo.CONTEXT_MASK);
        report.put("air_state_count",Blocks.AIR.getBlockState().getValidStates().size());
        report.put("air_is_base_state",air==Blocks.AIR.getBlockState().getBaseState());
        report.put("fluid_bridge_loaded",com.sumirelabs.pulsar.compat.FluidLightBridge.LOADED);
        List<Map<String,Object>> chunks=new ArrayList<>();Map<String,Integer> classes=new TreeMap<>();
        int empty=0,nonempty=0,absent=0;
        for(var chunk:world.getChunkProvider().getLoadedChunks()) {
            int ce=0,cn=0,ca=0;
            for(var section:chunk.getBlockStorageArray()) {
                if(section==null){ca++;continue;}
                classes.merge(section.getClass().getName(),1,Integer::sum);
                if(section.isEmpty())ce++;else cn++;
            }
            empty+=ce;nonempty+=cn;absent+=ca;
            Map<String,Object> row=new LinkedHashMap<>();row.put("x",chunk.x);row.put("z",chunk.z);row.put("empty",ce);row.put("nonempty",cn);row.put("absent",ca);chunks.add(row);
        }
        report.put("empty_sections",empty);report.put("nonempty_sections",nonempty);report.put("absent_sections",absent);report.put("section_classes",classes);report.put("chunks",chunks);
        List<Map<String,Object>> engines=new ArrayList<>();
        Object manager=((PulsarWorld)world).pulsar$getLightManager();
        for(String laneName:new String[]{"skyWorker","blockWorker"}) {
            var laneField=manager.getClass().getDeclaredField(laneName);laneField.setAccessible(true);Object lane=laneField.get(manager);
            if(lane==null)continue;
            var poolField=lane.getClass().getDeclaredField("enginePool");poolField.setAccessible(true);
            for(Object engine:(java.util.concurrent.ConcurrentLinkedDeque<?>)poolField.get(lane)) {
                Map<String,Object> row=new LinkedHashMap<>();row.put("lane",laneName);
                Class<?> type=engine.getClass();while(type!=null&&!type.getSimpleName().equals("LightEngineCache"))type=type.getSuperclass();
                for(String name:new String[]{"plainAir","cacheEmptyAir"}) {
                    try {var field=type.getDeclaredField(name);field.setAccessible(true);row.put(name,field.get(engine));}
                    catch(NoSuchFieldException missing){row.put(name,"absent");}
                }
                engines.add(row);
            }
        }
        report.put("cached_engine_flags",engines);
        Path out=world.getSaveHandler().getWorldDirectory().toPath().resolve("lightbench-results/cache-inspection.json");Files.writeString(out,new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

'''+needle)
p.write_bytes(t.encode());print('Prepared post-benchmark runtime eligibility/section inspection')
