package com.sumirelabs.pulsar.light.nativeprobe;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.init.*;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
/** Isolated A/B probe for all three real rendering paths, including optional Celeritas. */
@SuppressWarnings("unchecked")
public final class CornerLightProbe {
 static Class<?> view,test;
 static Method fixture,render;
 static boolean baseline=Boolean.getBoolean("issue20.baseline");
 static Object world(EnumFacing face,int light,int s,int t) throws Exception {
  Object w=fixture.invoke(null,face,light);BlockPos base=((BlockPos)field(w,"center")).offset(face);
  EnumFacing a=face.getAxis()==EnumFacing.Axis.X?EnumFacing.UP:EnumFacing.EAST;
  EnumFacing b=face.getAxis()==EnumFacing.Axis.Z?EnumFacing.UP:EnumFacing.SOUTH;
  for(String key:new String[]{"states","lights"}) {
   Map<BlockPos,Object> map=(Map<BlockPos,Object>)field(w,key);Map<BlockPos,Object> copy=new HashMap<>(map);map.clear();
   for(var e:copy.entrySet()) {
    int dx=e.getKey().getX()-base.getX(),dy=e.getKey().getY()-base.getY(),dz=e.getKey().getZ()-base.getZ();
    if(a.getAxis()==EnumFacing.Axis.X)dx*=s;else dy*=s;
    if(b.getAxis()==EnumFacing.Axis.Y)dy*=t;else dz*=t;
    map.put(base.add(dx,dy,dz),e.getValue());
   }
  }
  return w;
 }
 static Object field(Object w,String key)throws Exception {var f=view.getDeclaredField(key);f.setAccessible(true);return f.get(w);}
 static void check(String name,int[] lm) {
  boolean leaked=Arrays.stream(lm).anyMatch(i->i!=0);
  if(leaked!=baseline)throw new AssertionError(name+" expected leak="+baseline+Arrays.toString(lm));
 }
 static int[] vanilla(Object w,EnumFacing face)throws Exception{return (int[])render.invoke(null,w,face);}
 static int[] celeritas(Object w,EnumFacing face)throws Exception {
  Class<?> cacheType=Class.forName("org.taumc.celeritas.impl.render.terrain.compile.light.LightDataCache");
  Class<?> accessType=cacheType.getSuperclass();Class<?> faceType=Class.forName("org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing");
  Class<?> aoType=Class.forName("org.embeddedt.embeddium.impl.model.light.smooth.AoFaceData");
  var ctor=aoType.getDeclaredConstructor();ctor.setAccessible(true);
  var init=aoType.getDeclaredMethod("initLightData",accessType,int.class,int.class,int.class,faceType,boolean.class);init.setAccessible(true);
  var result=aoType.getDeclaredField("lm");result.setAccessible(true);
  Object cache=cacheType.getConstructor(IBlockAccess.class).newInstance(w);BlockPos center=(BlockPos)field(w,"center");
  accessType.getMethod("reset",int.class,int.class,int.class).invoke(cache,center.getX(),center.getY(),center.getZ());
  String sign=face.getAxisDirection()==EnumFacing.AxisDirection.POSITIVE?"POS_":"NEG_";
  Object direction=faceType.getField(sign+face.getAxis().name()).get(null);
  Object ao=ctor.newInstance();init.invoke(ao,cache,center.getX(),center.getY(),center.getZ(),direction,true);
  return ((int[])result.get(ao)).clone();
 }
 static int[] forge(Object w, EnumFacing face) throws Exception {
  var lighter = new net.minecraftforge.client.model.pipeline.VertexLighterSmoothAo(null);
  lighter.setWorld((IBlockAccess)w);
  lighter.setState(Blocks.STONE.getDefaultState());
  lighter.setBlockPos((BlockPos)field(w,"center"));
  lighter.updateBlockInfo();
  var update=lighter.getClass().getDeclaredMethod("updateLightmap",float[].class,float[].class,float.class,float.class,float.class);
  update.setAccessible(true);
  int[] result=new int[4];
  EnumFacing a=face.getAxis()==EnumFacing.Axis.X?EnumFacing.UP:EnumFacing.EAST;
  EnumFacing b=face.getAxis()==EnumFacing.Axis.Z?EnumFacing.UP:EnumFacing.SOUTH;
  int index=0;
  for(int s:new int[]{-1,1})for(int t:new int[]{-1,1}) {
   float x=face.getXOffset()+(s*a.getXOffset()+t*b.getXOffset())*.5f;
   float y=face.getYOffset()+(s*a.getYOffset()+t*b.getYOffset())*.5f;
   float z=face.getZOffset()+(s*a.getZOffset()+t*b.getZOffset())*.5f;
   float[] lm=new float[2];
   update.invoke(lighter,new float[]{face.getXOffset(),face.getYOffset(),face.getZOffset()},lm,x,y,z);
   result[index++]=Float.floatToIntBits(Math.max(lm[0],lm[1]));
  }
  return result;
 }
 static int[] light(int renderer,Object w,EnumFacing face)throws Exception {
  return renderer==0?vanilla(w,face):renderer==1?forge(w,face):celeritas(w,face);
 }
 public static void main(String[] args)throws Exception {
  Bootstrap.register();test=Class.forName("com.sumirelabs.pulsar.light.SmoothLightOcclusionTest");view=Class.forName(test.getName()+"$Fixture");
  if(Boolean.getBoolean("issue20.wall")) { StairWallProbe.run(); return; }
  if(Boolean.getBoolean("issue20.previous")) { PartialSurfaceProbe.run(true); return; }
  fixture=test.getDeclaredMethod("fixture",EnumFacing.class,int.class);fixture.setAccessible(true);
  render=test.getDeclaredMethod("render",view,EnumFacing.class);render.setAccessible(true);
  for(int renderer=0;renderer<3;renderer++) {
   boolean cel=renderer==2;
   if(cel&&!Boolean.getBoolean("issue20.celeritas"))continue;
   String name=cel?"CELERITAS":renderer==1?"FORGE":"VANILLA";int count=0;
   for(EnumFacing face:EnumFacing.VALUES)for(int s:new int[]{-1,1})for(int t:new int[]{-1,1})for(int light:new int[]{15<<4,15<<20,(15<<4)|(15<<20)}) {
    Object w=world(face,light,s,t);if(renderer==1) { if(Arrays.stream(light(renderer,w,face)).anyMatch(i->i!=0))throw new AssertionError("Forge enclosed source leak"); } else check(name+face+s+t,light(renderer,w,face));count++;
   }
   for(EnumFacing face:EnumFacing.VALUES) {
    Object w=world(face,15<<4,1,1);BlockPos base=((BlockPos)field(w,"center")).offset(face);
    EnumFacing a=face.getAxis()==EnumFacing.Axis.X?EnumFacing.UP:EnumFacing.EAST;
    ((Map<BlockPos,IBlockState>)field(w,"states")).put(base.offset(a),Blocks.AIR.getDefaultState());
    ((Map<BlockPos,Integer>)field(w,"lights")).put(base,13<<4);
    ((Map<BlockPos,Integer>)field(w,"lights")).put(base.offset(a),14<<4);
    int[] lm=light(renderer,w,face);
    if(Arrays.stream(lm).anyMatch(i->i==0))throw new AssertionError("Lost open light");
    System.out.println("OPEN "+name+face+Arrays.toString(lm));
   }
   for(EnumFacing face:new EnumFacing[]{EnumFacing.UP,EnumFacing.DOWN})for(boolean stairs:new boolean[]{false,true}) {
    Object w=world(face,0,1,1);BlockPos base=((BlockPos)field(w,"center")).offset(face);
    IBlockState partial=stairs?Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.HALF,face==EnumFacing.UP?BlockStairs.EnumHalf.BOTTOM:BlockStairs.EnumHalf.TOP)
     :Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF,face==EnumFacing.UP?BlockSlab.EnumBlockHalf.BOTTOM:BlockSlab.EnumBlockHalf.TOP);
    ((Map<BlockPos,IBlockState>)field(w,"states")).put(base.east(),partial);
    ((Map<BlockPos,Integer>)field(w,"lights")).clear();((Map<BlockPos,Integer>)field(w,"lights")).put(base.east(),15<<20);
    check(name+" partial "+face+stairs,light(renderer,w,face));count++;
   }
   System.out.println(name+"_MIXIN_PASS cases="+count+" baseline="+baseline);
  }
  PartialSurfaceProbe.run(baseline);
 }
}
