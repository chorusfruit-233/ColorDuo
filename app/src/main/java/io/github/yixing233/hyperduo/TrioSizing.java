/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

/** Pixel sizing shared by SystemUI and the actual-size preview. */
public final class TrioSizing {
    private TrioSizing() {}

    static final class Bounds {
        final float x, y, width, height;
        Bounds(float x, float y, float width, float height) {
            this.x=x; this.y=y; this.width=width; this.height=height;
        }
    }

    static Bounds bounds(TrioAppearance a) {
        TrioGeometry.setCapsuleLength(a.capsuleLength);
        final TrioGeometry.SplitRing split = a.ring && (a.splitMode == 1 || a.drawsSimPair() || a.signalIconScale != 100)
                ? new TrioGeometry.SplitRing(a) : null;
        float capMinX=TrioGeometry.CAP_INK_X, capMaxX=capMinX+TrioGeometry.capInkW();
        float capMinY=TrioGeometry.CAP_INK_TOP, capMaxY=TrioGeometry.CAP_INK_BOTTOM;
        if (a.capsule && a.drawsSimPair()) {
            float radius=4f*a.dotScaleFactor();
            float pitch=a.simStackPitch(radius);
            float rx=(18f*a.pairPitch/100f+radius)*a.signalIconScaleFactor();
            float ry=(pitch/2+radius+(a.pairShape==1?0:Math.abs(a.pairBend)))*a.signalIconScaleFactor();
            for(int z=0;z<3;z++) {
                boolean placed=false;
                for(int part:a.ringZones[z]) if(part==Prefs.PART_SIM_PAIR)placed=true;
                if(!placed)continue;
                float cy=z==0 ? TrioGeometry.capDotTopY()+pitch/2 : z==2
                        ? TrioGeometry.capDotBottomY()-pitch/2
                        : TrioGeometry.capInteriorCy(a.ringStroke*TrioGeometry.CAP_STROKE_RATIO)+a.capsuleRightDy;
                float cx=z==1 ? TrioGeometry.capPairCx(a.ringStroke*TrioGeometry.CAP_STROKE_RATIO,a)
                        +a.capsuleRightDx : TrioGeometry.capDotCx();
                capMinX=Math.min(capMinX,cx+a.pairX-rx);capMaxX=Math.max(capMaxX,cx+a.pairX+rx);
                capMinY=Math.min(capMinY,cy+a.pairY-ry);capMaxY=Math.max(capMaxY,cy+a.pairY+ry);
            }
        }
        if(a.capsule && a.signalDots()) {
            float scale=a.signalIconScaleFactor();
            float radius=TrioGeometry.CAP_DOT_R*a.dotScaleFactor()*scale;
            float rx=TrioGeometry.CAP_DOT_PITCH*1.5f*scale+radius;
            capMinX=Math.min(capMinX,TrioGeometry.capDotCx()-rx);capMaxX=Math.max(capMaxX,TrioGeometry.capDotCx()+rx);
            capMinY=Math.min(capMinY,TrioGeometry.capDotTopY()-radius);
            capMaxY=Math.max(capMaxY,TrioGeometry.capDotBottomY()+radius);
        }
        if (split != null) return new Bounds(split.minX, split.minY, split.width(), split.height());
        if (a.capsule) return new Bounds(capMinX,capMinY,capMaxX-capMinX,capMaxY-capMinY);
        if (a.rect) return new Bounds(TrioGeometry.RECT_INK_X,TrioGeometry.RECT_INK_Y,
                TrioGeometry.RECT_INK_W,TrioGeometry.RECT_INK_H);
        return new Bounds(TrioGeometry.INK_X,TrioGeometry.INK_Y,TrioGeometry.INK_W,TrioGeometry.INK_H);
    }

    public static final class Metrics {
        public final int width, height;
        public final float scale, effectivePercent;
        public final boolean limited;
        Metrics(int width,int height,float scale,float percent,boolean limited) {
            this.width=width;this.height=height;this.scale=scale;
            this.effectivePercent=percent;this.limited=limited;
        }
    }

    /** A larger signal reserves more space instead of reducing the battery's pixel scale. */
    public static Metrics measure(TrioAppearance a,float density,int maxHeight,int maxWidth) {
        if (a==null || !Float.isFinite(density) || density<=0 || maxHeight<=0 || maxWidth<=0)
            return new Metrics(0,0,0,0,false);
        Bounds b=bounds(a);
        float referenceHeight=a.capsule?TrioGeometry.CAP_INK_H
                :a.rect?TrioGeometry.RECT_INK_H:TrioGeometry.INK_H;
        float base=(a.capsule?15f:20f)*density/referenceHeight;
        float requested=base*a.glyphFit();
        float horizontalPadding=a.capsule?0:4f*density/base;
        float scale=Math.min(requested,Math.min(maxHeight/b.height,maxWidth/(b.width+horizontalPadding)));
        float effective=scale/base;
        // Preserve the old gutter for <=100%; larger drawings reserve their full width.
        float baseWidth=a.capsule?TrioGeometry.capInkW()*base:24f*density;
        float baseHeight=a.capsule?(TrioGeometry.CAP_INK_BOTTOM-TrioGeometry.CAP_INK_TOP)*base:20f*density;
        int width=Math.min(maxWidth,(int)Math.ceil(Math.max(baseWidth,(b.width+horizontalPadding)*scale)-0.001f));
        int height=Math.min(maxHeight,(int)Math.ceil(Math.max(baseHeight,b.height*scale)-0.001f));
        return new Metrics(width,height,scale,effective*100f,scale+0.0001f<requested);
    }

    static boolean transform(int width,int height,TrioAppearance a,float scale,float[] out) {
        if(width<=0 || height<=0 || a==null || out==null || out.length<3 || scale<=0) return false;
        Bounds b=bounds(a);
        // A vendor measure pass can temporarily grant less than the requested host size.
        scale=Math.min(scale,Math.min(width/b.width,height/b.height));
        out[0]=scale;
        out[1]=(width-b.width*scale)/2f-b.x*scale;
        out[2]=(height-b.height*scale)/2f-b.y*scale;
        return true;
    }
}
