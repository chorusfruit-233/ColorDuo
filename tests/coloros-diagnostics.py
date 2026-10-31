#!/usr/bin/env python3
"""Exercise device trace formatting, clip evidence and independent mirror throttling."""
from pathlib import Path
import ast
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
tree = ast.parse((ROOT / "tests/coloros-sizing.py").read_text())
fixtures = next(ast.literal_eval(node.value) for node in tree.body
                if isinstance(node, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == "fixtures" for target in node.targets))
fixtures.pop("io/github/yixing233/hyperduo/ColorOsSizingTest.java")
fixtures["io/github/yixing233/hyperduo/ColorOsDiagnosticsTest.java"] = r'''
package io.github.yixing233.hyperduo;
import android.graphics.*;
import android.view.*;
import java.util.*;
public final class ColorOsDiagnosticsTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static class QuickStatusBarHeader extends ViewGroup { }
    public static void main(String[] args){
        ViewGroup window=new ViewGroup();window.height=800;window.width=420;
        QuickStatusBarHeader header=new QuickStatusBarHeader();header.parent=window;
        header.height=26;header.width=420;header.params.height=26;header.paddingTop=10;
        ViewGroup meter=new ViewGroup();meter.parent=header;meter.height=20;
        View image=new View();image.parent=meter;image.width=90;image.height=30;
        image.measuredWidth=90;image.measuredHeight=30;
        TrioSettings settings=TrioSettings.defaults();settings.glyphScale=150;
        ColorOsSizing sizing=new ColorOsSizing();sizing.measure(meter,image,TrioAppearance.of(settings));
        ColorOsDiagnostics audit=new ColorOsDiagnostics();List<String> log=new ArrayList<>();
        audit.layout(meter,image,90,30,1.5f,java.util.List.of(),100,log::add);
        check(log.stream().anyMatch(x->x.contains("kind=layout")),"layout trace has an identifiable phase");
        check(log.stream().anyMatch(x->x.contains("nativeLpH=26")&&x.contains("allocatedH=40")),
              "trace distinguishes the native header limit from the requested allocation");
        check(log.stream().anyMatch(x->x.contains("size=420x26")&&x.contains("lp=-2x40")),
              "trace retains the actual post-layout size even when it disagrees with LayoutParams");
        int before=log.size();audit.layout(meter,image,90,30,1.5f,java.util.List.of(),500,log::add);
        check(log.size()==before,"periodic layout inspection is throttled");
        java.awt.image.BufferedImage bitmap=new java.awt.image.BufferedImage(90,30,2);
        java.awt.Graphics2D graphics=bitmap.createGraphics();graphics.setClip(0,5,90,20);
        Canvas canvas=new Canvas(graphics);Rect bounds=new Rect(0,0,90,30);
        audit.draw(meter,image,canvas,bounds,1.5f,500,log::add);
        check(log.stream().anyMatch(x->x.contains("kind=draw-cut")),"incoming top and bottom canvas cuts are recorded");
        check(log.stream().anyMatch(x->x.contains("drawable=0,0,90,30 canvasClip=0,5,90,25")),
              "trace records the pre-draw clip, before the module creates its own layer");
        before=log.size();audit.draw(meter,image,canvas,bounds,1.5f,600,log::add);
        check(log.size()==before,"repeated clipped frames do not flood the log");
        graphics.setClip(0,0,90,30);audit.draw(meter,image,canvas,bounds,1.5f,600,log::add);
        check(log.stream().anyMatch(x->x.contains("kind=draw-full")),"a full frame is independent of the clipped-frame throttle");
        ViewGroup mirror=new ViewGroup();mirror.id=3;mirror.parent=window;
        ViewGroup previous=ColorOsDiagnostics.enterMirror(mirror);
        try {audit.draw(meter,image,canvas,bounds,1.5f,600,log::add);}
        finally {ColorOsDiagnostics.leaveMirror(previous);}
        check(log.stream().anyMatch(x->x.contains("mirror=ViewGroup#qs_status_bar_container_layout")),
              "mirror drawing is recorded even when a main-bar full frame was already logged");
        check(log.stream().anyMatch(x->x.contains("mirror[0]=")),"replayed source and mirror hierarchies are both captured");
        check(log.stream().anyMatch(x->x.contains("matrix=[1.0, 0.0, 0.0, 0.0, 1.0")),"native canvas transforms are included");
        audit.layout(meter,image,90,30,1.5f,java.util.List.of(mirror),1101,log::add);
        check(log.stream().anyMatch(x->x.contains("mirror-layout-0[0]=")),"periodic snapshots capture cached mirror geometry without relying on drawable callbacks");
        check(image.height==30 && header.height==26,"diagnostics never change actual layout");
        graphics.dispose();sizing.release();
        System.out.println("Device clipping evidence and trace throttling: "+checks+" checks passed");
    }
}'''
with tempfile.TemporaryDirectory(prefix="colorduo-diagnostics-") as directory:
    temporary = Path(directory)
    sources = []
    for name, content in fixtures.items():
        path = temporary / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(path)
    sources += [path for path in (ROOT / "tests/shims").rglob("*.java")
                if path.relative_to(ROOT / "tests/shims").as_posix() not in fixtures]
    sources += [SOURCE / (name + ".java") for name in (
        "Prefs", "TrioSettings", "TrioAppearance", "TrioGeometry", "TrioZones", "TrioSizing",
        "TrioRenderer", "DuoPolicy", "ColorOsSizing", "ColorOsDiagnostics")]
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, *map(str, sources)], check=True)
    subprocess.run(["java", "-Djava.awt.headless=true", "-cp", directory,
                    "io.github.yixing233.hyperduo.ColorOsDiagnosticsTest"], check=True)
