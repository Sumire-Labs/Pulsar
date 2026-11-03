package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.dynamic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.util.*;

/** Plain Celeritas terrain: immutable server occlusion, interpolated positions and per-fragment light each frame. */
public final class DynamicTerrainRuntime {
    private static final int LIMIT=64,WIDTH=1024,ROWS=24;
    private static final int[] UNITS={8,9,10};
    private static final int[] LANES={2,1,0,3};
    private static final boolean PRESENT=DynamicTerrainRuntime.class.getClassLoader().getResource("org/embeddedt/embeddium/impl/render/shader/ShaderLoader.class")!=null;
    private static final Map<Integer,Program> PROGRAMS=new HashMap<>();
    private static final Slot[] SLOTS=new Slot[LIMIT];
    private static final FloatBuffer UPLOAD=BufferUtils.createFloatBuffer(WIDTH*ROWS*4);
    private static final FloatBuffer POSITIONS=BufferUtils.createFloatBuffer(LIMIT*4),EMISSIONS=BufferUtils.createFloatBuffer(LIMIT*4);
    private static final IntBuffer BOXES=BufferUtils.createIntBuffer(LIMIT*4);
    private static final int[] oldTextures=new int[3];
    private static RgbShaderAtlas atlas;
    private static World owner;
    private static volatile boolean enabled;
    private static boolean compiled,failed,bound,shaderPack;
    private static int texture,count,cx,cy,cz,oldActive;
    private static float fx,fy,fz;
    private static long previousFrame;
    private static final class Slot {int id;DynamicLightField field;DynamicLightMotion motion;}
    private static final Map<Integer,DynamicLightMotion> MOTIONS=new HashMap<>();
    private static final Map<Integer,DynamicLightHistory> HISTORY=new HashMap<>();
    private static final class Program {
        final int id,enable; final Map<String,Integer> locations=new HashMap<>();
        Program(int id){this.id=id;enable=loc("pulsarFrameEnabled");}
        int loc(String name){return locations.computeIfAbsent(name,n->GL20.glGetUniformLocation(id,n));}
        void integer(String name,int value){int at=loc(name);if(at>=0)GL20.glUniform1i(at,value);}
        void floating(String name,float value){int at=loc(name);if(at>=0)GL20.glUniform1f(at,value);}
    }
    private DynamicTerrainRuntime(){}
    public static boolean enabled(){return enabled;}
    public static void tick() {
        if(!PRESENT)return;
        Minecraft mc=Minecraft.getMinecraft();
        if(owner!=mc.world){owner=mc.world;Arrays.fill(SLOTS,null);MOTIONS.clear();HISTORY.clear();count=0;previousFrame=0;if(atlas!=null)atlas.clear();}
        var fields=DynamicLightClient.visualFields();
        shaderPack=RgbShaderRuntime.shaderPackInUse(owner);
        boolean next=owner!=null&&!failed&&compiled&&com.sumirelabs.pulsar.config.PulsarConfig.dynamicLights.smoothTerrainLighting
                &&DynamicLightClient.mode()!=DynamicLights.Mode.OFF&&!shaderPack
                &&fields.size()<=LIMIT&&fields.values().stream().allMatch(DynamicLightField::hasVisualData)
                &&(!DynamicLightClient.colored()||atlas!=null&&atlas.ready());
        if(next!=enabled){enabled=next;if(owner!=null&&mc.renderGlobal!=null)mc.renderGlobal.loadRenderers();}
    }
    public static void frame(float partialTicks) {
        if(!PRESENT||failed||!compiled||owner==null||DynamicLightClient.mode()==DynamicLights.Mode.OFF||shaderPack)return;
        try {
            Minecraft mc=Minecraft.getMinecraft();Entity camera=mc.getRenderViewEntity();if(camera==null)return;
            var eye=camera.getPositionEyes(partialTicks);
            cx=(int)Math.floor(eye.x);cy=(int)Math.floor(eye.y);cz=(int)Math.floor(eye.z);
            fx=(float)(eye.x-cx);fy=(float)(eye.y-cy);fz=(float)(eye.z-cz);
            if(DynamicLightClient.colored()){if(atlas==null)atlas=new RgbShaderAtlas(false);atlas.tick(owner);}
            var fields=DynamicLightClient.visualFields();
            if(fields.size()>LIMIT)return; // Retain the last valid frame until tick switches to the complete mesh fallback.
            long now=System.nanoTime();double dt=previousFrame==0?1:Math.min(.1,(now-previousFrame)/1e9);previousFrame=now;
            var sorted=new ArrayList<>(fields.entrySet());sorted.sort(Map.Entry.comparingByKey());
            MOTIONS.keySet().retainAll(fields.keySet());
            HISTORY.keySet().retainAll(fields.keySet());
            POSITIONS.clear();EMISSIONS.clear();BOXES.clear();int at=0;
            for(var entry:sorted) {
                DynamicLightField field=entry.getValue();if(!field.hasVisualData())continue;
                Slot slot=SLOTS[at];if(slot==null||slot.id!=entry.getKey()){slot=new Slot();slot.id=entry.getKey();slot.motion=MOTIONS.computeIfAbsent(slot.id,k->new DynamicLightMotion());SLOTS[at]=slot;}
                double x=field.x+.5,y=field.y+.5,z=field.z+.5;
                Entity entity=owner.getEntityByID(entry.getKey());
                if(entity!=null&&!entity.isDead) {
                    x=entity.lastTickPosX+(entity.posX-entity.lastTickPosX)*partialTicks;
                    y=entity.lastTickPosY+(entity.posY-entity.lastTickPosY)*partialTicks+(entity instanceof EntityPlayer?entity.getEyeHeight():entity.height*.5);
                    z=entity.lastTickPosZ+(entity.posZ-entity.lastTickPosZ)*partialTicks;
                }
                field=HISTORY.computeIfAbsent(entry.getKey(),k->new DynamicLightHistory()).select(field,owner.getTotalWorldTime(),x,y,z);
                if(slot.field!=field){upload(at,field);slot.field=field;}
                slot.motion.update(x,y,z,field.x+.5,field.y+.5,field.z+.5,entity!=null&&!entity.isDead,dt);
                POSITIONS.put((float)(slot.motion.x()-cx)).put((float)(slot.motion.y()-cy)).put((float)(slot.motion.z()-cz)).put(0);
                BOXES.put(field.x-field.radius-cx).put(field.y-field.radius-cy).put(field.z-field.radius-cz).put(field.side);
                int e=field.emission();EMISSIONS.put(e>>>8&15).put(e>>>4&15).put(e&15).put(e>>>12&15);++at;
            }
            for(int i=at;i<LIMIT;i++)SLOTS[i]=null;
            count=at;POSITIONS.flip();EMISSIONS.flip();BOXES.flip();
        }catch(RuntimeException|LinkageError error){failed=true;Pulsar.LOGGER.error("Smooth dynamic terrain lighting failed; using mesh lighting",error);}
    }
    private static void upload(int slot,DynamicLightField field) {
        int oldTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),unpack=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int alignment=GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),rowLength=GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int skipRows=GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS),skipPixels=GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS),swap=GL11.glGetInteger(GL11.GL_UNPACK_SWAP_BYTES);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0);GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS,0);GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS,0);GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES,0);
            if(texture==0) {
                texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,0);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA16F,WIDTH,ROWS*LIMIT,0,GL11.GL_RGBA,GL11.GL_FLOAT,(FloatBuffer)null);
            } else GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            int rows=(field.size()+WIDTH-1)/WIDTH;UPLOAD.clear();
            for(int i=0;i<rows*WIDTH;i++)for(int lane:LANES) {
                int loss=i<field.size()?field.visualLoss(i,lane):DynamicLightField.BLOCKED;
                UPLOAD.put(loss==DynamicLightField.BLOCKED?1024f:loss/256f);
            }
            UPLOAD.flip();GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,slot*ROWS,WIDTH,rows,GL11.GL_RGBA,GL11.GL_FLOAT,UPLOAD);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldTexture);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpack);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,alignment);GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,rowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS,skipRows);GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS,skipPixels);GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES,swap);
        }
    }
    public static void bind(int handle) {
        Program program=PROGRAMS.computeIfAbsent(handle,Program::new);if(program.enable<0){unbind();return;}
        // Active sampler types must never alias even while the feature branch is disabled.
        program.integer("pulsarDynamicLoss",UNITS[0]);program.integer("pulsarLightData",UNITS[1]);program.integer("pulsarLightIndex",UNITS[2]);
        compiled=true;GL20.glUniform1i(program.enable,enabled?1:0);if(!enabled)return;
        if(bound)unbind();oldActive=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for(int i=0;i<UNITS.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+UNITS[i]);oldTextures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);}
        bound=true;
        GL13.glActiveTexture(GL13.GL_TEXTURE0+UNITS[0]);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        if(atlas!=null){GL13.glActiveTexture(GL13.GL_TEXTURE0+UNITS[1]);GL11.glBindTexture(GL11.GL_TEXTURE_2D,atlas.dataTexture());GL13.glActiveTexture(GL13.GL_TEXTURE0+UNITS[2]);GL11.glBindTexture(GL11.GL_TEXTURE_2D,atlas.indexTexture());}
        GL13.glActiveTexture(oldActive);
        program.integer("pulsarDynamicCount",count);program.integer("pulsarDynamicLoss",UNITS[0]);
        program.integer("pulsarDynamicRgb",DynamicLightClient.colored()?1:0);program.integer("pulsarDynamicProjected",DynamicLightClient.projected()?1:0);
        if(count>0){GL20.glUniform4(program.loc("pulsarDynamicPosition[0]"),POSITIONS);GL20.glUniform4(program.loc("pulsarDynamicBox[0]"),BOXES);GL20.glUniform4(program.loc("pulsarDynamicEmission[0]"),EMISSIONS);}
        GL20.glUniform3i(program.loc("pulsarCameraModulo"),Math.floorMod(cx,128),Math.floorMod(cy,64),Math.floorMod(cz,128));
        GL20.glUniform3f(program.loc("pulsarViewFraction"),fx,fy,fz);
        program.integer("pulsarStaticRgb",DynamicLightClient.colored()?1:0);program.integer("pulsarBlendMode",RgbTintBlendMode.current.ordinal());
        program.integer("pulsarLightData",UNITS[1]);program.integer("pulsarLightIndex",UNITS[2]);program.integer("pulsarLightEnabled",atlas!=null&&atlas.ready()?1:0);
        program.integer("pulsarLightSide",atlas==null?1:atlas.side());program.integer("pulsarLightMinSection",atlas==null?0:atlas.minSection());program.integer("pulsarLightSections",atlas==null?0:atlas.sectionCount());
        program.integer("pulsarCameraBlockX",cx);program.integer("pulsarCameraBlockY",cy);program.integer("pulsarCameraBlockZ",cz);
        program.floating("pulsarCameraFractX",0);program.floating("pulsarCameraFractY",0);program.floating("pulsarCameraFractZ",0);
        program.floating("pulsarSkyScale",RgbClient.shaderSkyScale());program.integer("pulsarHasSky",owner!=null&&owner.provider.hasSkyLight()?1:0);
    }
    public static void unbind() {
        if(!bound)return;
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for(int i=0;i<UNITS.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+UNITS[i]);GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldTextures[i]);}
        GL13.glActiveTexture(active);bound=false;
    }
    public static void deleted(int handle){PROGRAMS.remove(handle);}
}
