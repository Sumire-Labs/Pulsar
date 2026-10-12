from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=R/'lightbench-profile/src/main/java/com/sumirelabs/lightbench/LightProbe.java';t=p.read_text(encoding='utf-8')
t=t.replace('    private Object diagnosticWorld;','    private Object diagnosticWorld;\n    private boolean diagnosticEdit;')
a=t.index('    public void beginDiagnosticEdit()');b=t.index('    private final Engine engine;',a)
t=t[:a]+'''    public void beginDiagnosticEdit() throws Exception {
        if (this.engine == Engine.PULSAR && (WAIT_MODE.equals("caller") || WAIT_MODE.equals("gated"))) {
            this.diagnosticBegin.invoke(null,this.diagnosticWorld);
            this.diagnosticEdit=true;
        }
    }

    public void endDiagnosticEdit() throws Exception {
        if (this.engine == Engine.PULSAR && WAIT_MODE.equals("caller") && this.diagnosticEdit)
            this.diagnosticEnd.invoke(null,this.diagnosticWorld);
        this.diagnosticEdit=false;
    }

'''+t[b:]
t=t.replace('if(WAIT_MODE.equals("gated")) this.diagnosticEnd.invoke(null,this.diagnosticWorld);','if(WAIT_MODE.equals("gated") && this.diagnosticEdit) this.diagnosticEnd.invoke(null,this.diagnosticWorld);')
t=t.replace('"pulsar_chunk_future_then_global_pending"','"diagnostic_"+WAIT_MODE+"_then_global_pending"')
p.write_text(t,encoding='utf-8')
print('Finalized guarded diagnostic phases')
