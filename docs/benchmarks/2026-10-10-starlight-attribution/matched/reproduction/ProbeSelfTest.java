import com.sumirelabs.benchprobe.BenchTiming;
import com.google.gson.JsonParser;
import java.nio.file.*;
public class ProbeSelfTest {
    public static void main(String[] args)throws Exception{
        BenchTiming.prepare();int index=0;while(!BenchTiming.selected("block_place",index))index++;
        if(!BenchTiming.ENABLED){if(BenchTiming.begin("block_place",index)||BenchTiming.engineStart(true)!=0)throw new AssertionError();System.out.println("Disabled capture inert");return;}
        int warm=0;while(!BenchTiming.selected("warmup_block_place",warm))warm++;
        BenchTiming.begin("warmup_block_place",warm);long wa=BenchTiming.engineStart(true);BenchTiming.engine(false,wa,wa,wa,wa,wa,wa);BenchTiming.work(false,999,999,999);BenchTiming.end(1,2,3,0);BenchTiming.discardWarmup();
        if(!BenchTiming.begin("block_place",index)||BenchTiming.engineStart(false)!=0)throw new AssertionError();
        long origin=System.nanoTime(),task=BenchTiming.taskStart(false,origin),a=BenchTiming.engineStart(true);
        BenchTiming.propagation(false,a,a+2,a+4);BenchTiming.work(false,1,12,3);
        BenchTiming.engine(false,a,a+10,a+20,a+30,a+40,a+50);BenchTiming.taskEnd(false,task);
        BenchTiming.end(100,200,System.nanoTime()-origin,origin);
        if(!BenchTiming.begin("block_place",index))throw new AssertionError();
        a=BenchTiming.engineStart(true);long b=BenchTiming.engineStart(true);
        BenchTiming.engine(true,a,a,a,a,a,a);BenchTiming.engine(false,b,b,b,b,b,b);
        BenchTiming.end(1,2,3,0);
        Path p=Path.of(args[0]);BenchTiming.write(p);var rows=JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonArray("samples");var x=rows.get(0).getAsJsonObject();
        if(rows.size()!=2||x.get("overlap").getAsBoolean()||!rows.get(1).getAsJsonObject().get("overlap").getAsBoolean()
           ||x.get("block_setup_nanos").getAsLong()!=10||x.get("block_tasks").getAsLong()!=1||x.get("block_increase_entries").getAsLong()!=12
           ||x.get("sky_engines").getAsLong()!=0)throw new AssertionError();
        System.out.println("Capture selection, stage attribution, task counts, client exclusion and overlap rejection passed");
    }
}
