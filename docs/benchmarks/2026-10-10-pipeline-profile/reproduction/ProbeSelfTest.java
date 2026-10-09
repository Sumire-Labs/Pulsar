import com.sumirelabs.pulsar.light.PhaseTiming;
import com.google.gson.JsonParser;
import java.nio.file.*;
public class ProbeSelfTest {
    public static void main(String[] args) throws Exception {
        PhaseTiming.begin("test");
        if(!PhaseTiming.ENABLED) {
            if(PhaseTiming.current()!=null||PhaseTiming.clock(true)!=0)throw new AssertionError();
            System.out.println("Disabled probe is inert");return;
        }
        try {PhaseTiming.begin("overlap");throw new AssertionError();}catch(IllegalStateException expected){}
        if(PhaseTiming.clock(false)!=0)throw new AssertionError("Client captured");
        PhaseTiming.Sample sample=PhaseTiming.current();
        Thread[] threads=new Thread[2];
        for(int i=0;i<2;i++){final boolean sky=i==0;threads[i]=new Thread(()->{
            for(int j=0;j<100;j++){
                PhaseTiming.engine(sky,1,11,21,31,41,51);
                PhaseTiming.propagation(sky,1,5,11);
                PhaseTiming.task(sample,sky,110,120,140);
            }
        });threads[i].start();}
        for(Thread t:threads)t.join();
        PhaseTiming.end(10,20,30,100);
        PhaseTiming.begin("second");PhaseTiming.end(1,2,3,100);
        Path out=Path.of(args[0]);PhaseTiming.write(out);
        var rows=JsonParser.parseString(Files.readString(out)).getAsJsonObject().getAsJsonArray("samples");
        for(String lane:new String[]{"sky_","block_"}) {
            var first=rows.get(0).getAsJsonObject();
            if(first.get(lane+"tasks").getAsLong()!=100||first.get(lane+"engine_setup_nanos").getAsLong()!=1000
                ||first.get(lane+"first_task_start_offset_nanos").getAsLong()!=20
                ||first.get(lane+"last_task_end_offset_nanos").getAsLong()!=40
                ||rows.get(1).getAsJsonObject().get(lane+"tasks").getAsLong()!=0)throw new AssertionError(lane);
        }
        System.out.println("Concurrent lane attribution, sample reset, timestamps and overlap guard passed");
    }
}
