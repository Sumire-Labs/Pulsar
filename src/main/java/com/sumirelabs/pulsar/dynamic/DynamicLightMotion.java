package com.sumirelabs.pulsar.dynamic;

/** Client render position only; never changes the server's gameplay light. */
public final class DynamicLightMotion {
    private double x,y,z;
    private boolean initialized;
    public double x(){return x;}
    public double y(){return y;}
    public double z(){return z;}
    public void update(double targetX,double targetY,double targetZ,double centerX,double centerY,double centerZ,
                       boolean interpolatedEntity,double elapsedSeconds) {
        double dx=targetX-centerX,dy=targetY-centerY,dz=targetZ-centerZ;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz),limit=DynamicLightField.MAX_RENDER_OFFSET;
        boolean clamped=length>limit;
        if(clamped){double scale=limit/length;targetX=centerX+dx*scale;targetY=centerY+dy*scale;targetZ=centerZ+dz*scale;}
        double jump=(x-targetX)*(x-targetX)+(y-targetY)*(y-targetY)+(z-targetZ)*(z-targetZ);
        // Minecraft already interpolated this entity for the current rendered frame.
        // Smoothing it again adds visible latency, particularly when changing direction.
        double blend=!initialized||jump>64||interpolatedEntity&&!clamped?1:1-Math.exp(-Math.min(.1,Math.max(0,elapsedSeconds))*40);
        x+=(targetX-x)*blend;y+=(targetY-y)*blend;z+=(targetZ-z)*blend;initialized=true;
        dx=x-centerX;dy=y-centerY;dz=z-centerZ;length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(length>limit){double scale=limit/length;x=centerX+dx*scale;y=centerY+dy*scale;z=centerZ+dz*scale;}
    }
}
