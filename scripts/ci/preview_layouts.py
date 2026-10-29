#!/usr/bin/env python3
"""Render layout references with production drawing code and the desktop Canvas shim."""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"

SCENES = r'''
package io.github.yixing233.hyperduo;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

public final class LayoutReference {
    public static void main(String[] args) throws Exception {
        BufferedImage board = new BufferedImage(1080, 740, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = board.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x111820)); g.fillRect(0, 0, board.getWidth(), board.getHeight());
        g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 25));
        g.drawString("ColorDuo / PR #7 layout reference", 28, 42);
        g.setColor(new Color(0xAAB8C8)); g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("Production renderer + desktop Canvas. Simulated data; not a phone screenshot.", 28, 68);
        String[] titles = {"01  Classic / single SIM", "02  Split arcs / single SIM",
                "03  Split / paired SIMs", "04  Split / independent sides",
                "05  Capsule / paired SIMs", "06  Split / fast charging"};
        String[] notes = {"Existing layout", "Linked half ellipses", "Physical SIM 1 = 4; SIM 2 = 2",
                "Left 40 x 110; right 24 x 72", "Clock left; two SIM rows in centre",
                "Paired rows remain while charging"};
        for (int scene = 0; scene < titles.length; scene++) {
            TrioSettings settings = TrioSettings.defaults();
            settings.trioStyle = scene == 4 ? Prefs.STYLE_CAPSULE : Prefs.STYLE_RING;
            settings.showMobile = settings.showSim1 = settings.showSim2 = true;
            settings.mobileTypeMode = Prefs.MOBILE_TYPE_IN_RING;
            settings.signalMode = Prefs.SIGNAL_IN_RING;
            settings.splitMode = scene == 0 || scene == 4 ? 0 : 1;
            settings.ringZones = Prefs.decodeLayout(scene < 2 ? "3|1,5|7|"
                    : scene == 4 ? "3|9|1,5|6" : scene == 5 ? "4,3|9|1,5|" : "3|9|1,5|");
            if (scene == 3) {
                settings.splitLink = 0;
                settings.splitRightBend = 24;
                settings.splitRightHeight = 72;
                settings.splitRightY = 12;
                settings.splitRightTop = settings.splitRightBottom = 20;
            }
            TrioState state = new TrioState();
            state.level = 84;
            state.useTint = true;
            state.tintColor = 0xfff3f7fe;
            state.slotLevels = scene < 2 ? new int[] {4, -1} : new int[] {4, 2};
            state.sims = scene < 2 ? 1 : 2;
            state.mobileLevel = 4;
            state.mobileService = DuoPolicy.Radio.AVAILABLE;
            state.mobileType = "5G";
            state.wifiPresent = true;
            state.wifiLevel = 3;
            state.charging = state.quickCharging = scene == 5;
            TrioState.sClockText = "09:41";
            BufferedImage icon = new BufferedImage(280, 200, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ink = icon.createGraphics();
            TrioRenderer.drawState(new android.graphics.Canvas(ink), 280, 200, state, settings);
            ink.dispose();
            int x = 20 + scene % 3 * 354, y = 92 + scene / 3 * 310;
            g.setColor(new Color(0x1C2733)); g.fillRoundRect(x, y, 334, 292, 18, 18);
            g.drawImage(icon, x + 27, y + 24, null);
            g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 17));
            g.drawString(titles[scene], x + 16, y + 242);
            g.setColor(new Color(0xAAB8C8)); g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString(notes[scene], x + 16, y + 266);
        }
        g.dispose();
        Path output = Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        ImageIO.write(board, "png", output.toFile());
        if (args.length > 1) mirrorReference(Path.of(args[1]));
    }

    private static void mirrorReference(Path output) throws Exception {
        BufferedImage board=new BufferedImage(800,650,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=board.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x111820));g.fillRect(0,0,800,650);
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,24));
        g.drawString("Dot curves / vertical mirror",24,36);
        g.setFont(new Font("SansSerif",Font.PLAIN,13));g.setColor(new Color(0xAAB8C8));
        g.drawString("Production renderer; simulated readings. Every row flips; SIM order stays fixed.",24,61);
        for(int cards=1;cards<=2;cards++)for(int mirror=0;mirror<=1;mirror++) {
            TrioSettings settings=TrioSettings.defaults();settings.splitMode=1;
            settings.signalMode=Prefs.SIGNAL_IN_RING;settings.showMobile=settings.showSim1=settings.showSim2=true;
            settings.ringZones=Prefs.decodeLayout("3|9||");settings.pairBend=24;settings.pairMirror=mirror;
            TrioState state=new TrioState();state.level=84;state.slotLevels=cards==1?new int[]{4,-1}:new int[]{4,2};
            state.sims=cards;state.mobileService=DuoPolicy.Radio.AVAILABLE;
            state.useTint=true;state.tintColor=0xfff3f7fe;
            BufferedImage ink=new BufferedImage(270,180,BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig=ink.createGraphics();
            TrioRenderer.drawState(new android.graphics.Canvas(ig),270,180,state,settings);ig.dispose();
            int x=20+mirror*390,y=82+(cards-1)*278;
            g.setColor(new Color(0x1C2733));g.fillRoundRect(x,y,370,258,16,16);
            g.drawImage(ink,x+50,y+15,null);
            g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,18));
            g.drawString((cards==1?"Single SIM":"Two SIMs")+" / mirror "+(mirror==0?"OFF":"ON"),x+20,y+222);
        }
        g.dispose();Files.createDirectories(output.toAbsolutePath().getParent());
        ImageIO.write(board,"png",output.toFile());
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mirror-output", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    with tempfile.TemporaryDirectory(prefix="colorduo-layouts-") as directory:
        classes = Path(directory)
        scene = classes / "LayoutReference.java"
        scene.write_text(SCENES)
        sources = sorted((ROOT / "tests/shims").rglob("*.java"))
        sources += [SOURCE / f"{name}.java" for name in (
            "Prefs", "TrioSettings", "TrioAppearance", "TrioGeometry", "TrioZones", "TrioRenderer", "DuoPolicy")]
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(classes),
                        *map(str, sources), str(scene)], check=True)
        subprocess.run(["java", "-Djava.awt.headless=true", "-Xmx512m", "-cp", str(classes),
                        "io.github.yixing233.hyperduo.LayoutReference", str(output),
                        *([str(args.mirror_output.resolve())] if args.mirror_output else [])], check=True)
    print(output)


if __name__ == "__main__":
    main()
