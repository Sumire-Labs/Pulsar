const fs=require('fs'),zlib=require('zlib');
(async()=>{
 const {parseNbt,child,set,encodeTag}=await import('../pulsar-research-20260905/visual-comparison-20260906/nbt-read.mjs');
 const [world,variant,mode,run,command]=process.argv.slice(2);
 if(!command) throw Error('Explicit benchmark command required');
 const nbt=parseNbt(zlib.gunzipSync(fs.readFileSync(world+'/level.dat')));
 const data=child(nbt,'Data'); set(data,'LevelName',8,'Bench Updates');
 set(child(data,'GameRules'),'gameLoopFunction',8,'perfbench:start');
 fs.writeFileSync(world+'/level.dat',zlib.gzipSync(encodeTag(nbt)));
 const dir=world+'/data/functions/perfbench';fs.mkdirSync(dir,{recursive:true});
 fs.writeFileSync(dir+'/start.mcfunction','scoreboard objectives add benchwait dummy\ngamerule gameLoopFunction perfbench:wait\n');
 fs.writeFileSync(dir+'/wait.mcfunction','scoreboard players add @a benchwait 1\nexecute @a[score_benchwait_min=200] ~ ~ ~ function perfbench:setup\n');
 fs.writeFileSync(dir+'/setup.mcfunction','scoreboard players set @a benchwait 0\ngamerule gameLoopFunction perfbench:settle\ngamerule doMobSpawning false\ngamerule randomTickSpeed 0\ngamerule doFireTick false\ngamerule doWeatherCycle false\nweather clear\ntime set 6000\ngamerule doDaylightCycle false\ngamemode spectator @a\nexecute @a ~ ~ ~ tp @s 20000 280 20072 180 25\n');
 fs.writeFileSync(dir+'/settle.mcfunction','scoreboard players add @a benchwait 1\nexecute @a[score_benchwait_min=100] ~ ~ ~ function perfbench:run\n');
 fs.writeFileSync(dir+'/run.mcfunction',`gamerule gameLoopFunction -\nexecute @a ~ ~ ~ ${command}\ngamerule fourbench_complete true\nsay FOUR_ENGINE_COMMAND_RETURNED\n`);
})().catch(e=>{console.error(e);process.exitCode=1});
