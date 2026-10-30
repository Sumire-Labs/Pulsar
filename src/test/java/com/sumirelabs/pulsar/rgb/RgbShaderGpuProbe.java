package com.sumirelabs.pulsar.rgb;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.nio.charset.StandardCharsets;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Hidden 1x1 framebuffer test of the shipped GLSL; does not open a game or user world. */
public final class RgbShaderGpuProbe {
    private static int program,indexTexture;
    public static void main(String[] args) throws Exception {
        if(!glfwInit())throw new AssertionError("GLFW initialization failed");
        long window=0;
        try {
            glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
            glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
            window=glfwCreateWindow(16,16,"Pulsar GPU test",0,0);
            if(window==0)throw new AssertionError("OpenGL context unavailable");
            glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println("GPU: "+glGetString(GL_RENDERER)+" / "+glGetString(GL_VERSION));
            String helpers;
            try(var input=RgbShaderGpuProbe.class.getResourceAsStream("/assets/pulsar/shaders/lightfield.glsl")) {
                if(input==null)throw new AssertionError("Missing lightfield resource");
                helpers=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            }
            String vertex="#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}";
            String fragment="#version 330 core\n"+helpers+"\nuniform vec3 position;uniform vec2 coordinates;out vec4 result;void main(){result=pulsarLightmap(vec4(.8,.7,.6,.25),position,coordinates);}";
            program=glCreateProgram();glAttachShader(program,compile(GL_VERTEX_SHADER,vertex));glAttachShader(program,compile(GL_FRAGMENT_SHADER,fragment));
            glLinkProgram(program);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new AssertionError(glGetProgramInfoLog(program));
            glUseProgram(program);
            indexTexture=glGenTextures();glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,indexTexture);nearest();
            glUniform1i(location("pulsarLightIndex"),0);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,glGenTextures());nearest();
            glTexImage2D(GL_TEXTURE_2D,0,GL_R32UI,1,1,0,GL_RED_INTEGER,GL_UNSIGNED_INT,new int[]{0});
            glUniform1i(location("pulsarLightData"),1);
            int fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            glActiveTexture(GL_TEXTURE2);int target=glGenTextures();glBindTexture(GL_TEXTURE_2D,target);nearest();
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,1,1,0,GL_RGBA,GL_FLOAT,(java.nio.ByteBuffer)null);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Incomplete framebuffer");
            glViewport(0,0,1,1);glBindVertexArray(glGenVertexArrays());
            glUniform1i(location("pulsarLightSide"),1);glUniform1i(location("pulsarLightSections"),1);
            glUniform1i(location("pulsarHasSky"),1);glUniform1i(location("pulsarLightEnabled"),1);
            glUniform1f(location("pulsarSkyScale"),1);glUniform3f(location("position"),8,8,8);glUniform2f(location("coordinates"),.5f,.5f);
            field(0xF00,0x00F,15,15);
            expect("mixed RGB_MAX",.8f,0,.8f,.25f);
            glUniform1i(location("pulsarLightEnabled"),0);expect("disabled",.8f,.7f,.6f,.25f);
            glUniform1i(location("pulsarLightEnabled"),1);glUniform2f(location("coordinates"),1,1);expect("fullbright",.8f,.7f,.6f,.25f);
            glUniform2f(location("coordinates"),.5f,.5f);glUniform3f(location("position"),-8,8,8);expect("unloaded neighbour",.8f,.7f,.6f,.25f);
            glUniform3f(location("position"),8,8,8);field(0,0,15,0);expect("absorbed VANILLA",.8f*.6f/15.6f,.7f*.6f/15.6f,.6f*.6f/15.6f,.25f);
            field(0xFFF,0xFFF,15,15);expect("white",.8f,.7f,.6f,.25f);
            field(0x700,0,7,0);expect("dim red glass",.8f,0,0,.25f);
            field(0,0x600,0,6);glUniform1f(location("pulsarSkyScale"),4/15f);expect("filtered night sky",.8f,.7f,.6f,.25f);
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL error");
            System.out.println("RGB_GPU_PASS cases=8");
        } finally {if(window!=0)glfwDestroyWindow(window);glfwTerminate();}
    }
    private static int compile(int type,String text){int shader=glCreateShader(type);glShaderSource(shader,text);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==0)throw new AssertionError(glGetShaderInfoLog(shader));return shader;}
    private static int location(String name){return glGetUniformLocation(program,name);}
    private static void nearest(){glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);}
    private static void field(int block,int sky,int canonical,int skyCanonical){glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,indexTexture);int[] entries=new int[256*4];entries[0]=-2;entries[1]=RgbShaderLightData.pack(block,sky,canonical,skyCanonical);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32I,256,1,0,GL_RGBA_INTEGER,GL_INT,entries);}
    private static void expect(String name,float... expected){glDrawArrays(GL_TRIANGLES,0,3);var values=BufferUtils.createFloatBuffer(4);glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,values);for(int i=0;i<4;i++)if(Math.abs(values.get(i)-expected[i])>.0002f)throw new AssertionError(name+" component="+i+" expected="+expected[i]+" actual="+values.get(i));}
}
