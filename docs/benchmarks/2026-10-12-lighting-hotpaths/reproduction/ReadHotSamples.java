import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

public class ReadHotSamples {
  public static void main(String[] args) throws Exception {
    Map<String,Map<String,Long>> counts=new TreeMap<>();
    Map<String,Long> total=new TreeMap<>();
    try(RecordingFile recording=new RecordingFile(Path.of(args[0]))) {
      while(recording.hasMoreEvents()) {
        RecordedEvent e=recording.readEvent();
        if(!e.getEventType().getName().equals("jdk.ExecutionSample")) continue;
        RecordedStackTrace stack=e.getStackTrace();if(stack==null)continue;
        List<RecordedFrame> frames=stack.getFrames();
        boolean changed=false,sky=false;
        for(RecordedFrame f:frames) {
          String name=f.getMethod().getType().getName();
          changed|=f.getMethod().getName().equals("blocksChangedInChunk");
          sky|=name.contains("ScalarSkyEngine")||name.contains("SkyStarLightEngine");
        }
        if(!changed)continue;
        String lane=sky?"sky":"block";
        total.merge(lane,1L,Long::sum);
        RecordedFrame f=frames.getFirst();
        String name=f.getMethod().getType().getName()+"."+f.getMethod().getName()+":"+f.getLineNumber();
        counts.computeIfAbsent(lane,x->new HashMap<>()).merge(name,1L,Long::sum);
      }
    }
    for(var lane:counts.entrySet()) {
      System.out.println("TOTAL\t"+lane.getKey()+"\t"+total.get(lane.getKey()));
      lane.getValue().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(40).forEach(row->System.out.println(lane.getKey()+"\t"+row.getValue()+"\t"+row.getKey()));
    }
  }
}
