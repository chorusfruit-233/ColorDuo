/*
 * Copyright (C) 2026 yixing233 and HyperDuo contributors
 *
 * This file is part of HyperDuo, released under the GNU General Public
 * License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.yixing233.hyperduo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.Arrays;

/** One arc-length colour field for the complete charged outline, including its caps. */
final class ChargeFlowRenderer {
    private static final Field[] CACHE=new Field[3];
    private static int next;
    private static final Paint BITMAP_PAINT=new Paint(Paint.ANTI_ALIAS_FLAG);
    static {BITMAP_PAINT.setFilterBitmap(true);}

    static void draw(Canvas canvas,int level,int role,TrioAppearance a,float phase,
                     float p0,float p1,float sweep,float start) {
        if(phase<0||level<=0)return;
        Field field=null;
        for(Field cached:CACHE)if(cached!=null&&cached.matches(a,p0,p1,sweep,start)){field=cached;break;}
        if(field==null) {
            field=new Field(a,p0,p1,sweep,start);
            CACHE[next]=field;next=(next+1)%CACHE.length;
        }
        field.draw(canvas,Math.min(100,level),role,a,phase);
    }

    private static final class Segment {
        final float x,y,dx,dy,length,offset;
        boolean first,last;
        Segment(float x,float y,float x1,float y1,float offset) {
            this.x=x;this.y=y;dx=x1-x;dy=y1-y;length=(float)Math.hypot(dx,dy);this.offset=offset;
        }
    }
    private static final class Route {
        final ArrayList<Segment> segments=new ArrayList<>();
        float length,minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        boolean closed;
        void line(float x,float y,float x1,float y1) {
            Segment s=new Segment(x,y,x1,y1,length);if(s.length<0.00001f)return;
            segments.add(s);length+=s.length;
            minX=Math.min(minX,Math.min(x,x1));maxX=Math.max(maxX,Math.max(x,x1));
            minY=Math.min(minY,Math.min(y,y1));maxY=Math.max(maxY,Math.max(y,y1));
        }
        void arc(float cx,float cy,float rx,float ry,float start,float sweep) {
            int steps=Math.max(1,(int)Math.ceil(Math.abs(sweep)/2f));
            double angle=Math.toRadians(start);
            float x=cx+rx*(float)Math.cos(angle),y=cy+ry*(float)Math.sin(angle);
            for(int i=1;i<=steps;i++) {
                angle=Math.toRadians(start+sweep*i/steps);
                float x1=cx+rx*(float)Math.cos(angle),y1=cy+ry*(float)Math.sin(angle);
                line(x,y,x1,y1);x=x1;y=y1;
            }
        }
        void finish() {
            int contours=0;
            for(int i=0;i<segments.size();i++) {
                Segment s=segments.get(i),previous=i==0?null:segments.get(i-1);
                s.first=previous==null||Math.hypot(s.x-previous.x-previous.dx,s.y-previous.y-previous.dy)>.01f;
                if(s.first){contours++;if(previous!=null)previous.last=true;}
            }
            if(segments.isEmpty())return;
            Segment first=segments.get(0),last=segments.get(segments.size()-1);last.last=true;
            closed=contours==1&&Math.hypot(first.x-last.x-last.dx,first.y-last.y-last.dy)<.01f;
            if(closed){first.first=false;last.last=false;}
        }
        Segment at(float distance) {
            for(Segment s:segments)if(distance<=s.offset+s.length)return s;
            return segments.get(segments.size()-1);
        }
    }

    private static Route route(TrioAppearance a,float p0,float p1,float sweep,float start) {
        Route r=new Route();
        if(a.capsule) {
            float left=TrioGeometry.CAP_LEFT,right=TrioGeometry.capRight(),radius=TrioGeometry.CAP_RADIUS;
            float top=TrioGeometry.CAP_TOP,bottom=TrioGeometry.CAP_BOTTOM,cx=(left+right)/2,cy=(top+bottom)/2;
            r.line(cx-p1,bottom,left+radius,bottom);
            r.arc(left+radius,cy,radius,radius,90,180);
            r.line(left+radius,top,cx-p0,top);
            r.line(cx+p0,top,right-radius,top);
            r.arc(right-radius,cy,radius,radius,270,180);
            r.line(right-radius,bottom,cx+p1,bottom);
        } else if(a.ring&&a.splitMode==1) {
            TrioGeometry.SplitRing split=new TrioGeometry.SplitRing(a);
            for(int side=0;side<2;side++)for(int n=1;n<=TrioGeometry.SplitRing.STEPS;n++) {
                int i=side*(TrioGeometry.SplitRing.STEPS+1)+n;
                r.line(split.x[i-1],split.y[i-1],split.x[i],split.y[i]);
            }
        } else {
            float from=Math.max(0,Math.min(1,p0)),to=Math.max(0,Math.min(1,p1));
            if(from>0)r.arc(TrioGeometry.B_CX,TrioGeometry.B_CY,TrioGeometry.B_R,TrioGeometry.B_R,start,sweep*from);
            if(to<1)r.arc(TrioGeometry.B_CX,TrioGeometry.B_CY,TrioGeometry.B_R,TrioGeometry.B_R,start+sweep*to,sweep*(1-to));
        }
        r.finish();return r;
    }

    /** Cached geometry and coverage. A frame only fills a palette and recolours covered pixels. */
    private static final class Field {
        final TrioAppearance geometry;
        final float p0,p1,sweep,start,capRight,stroke,scale,left,top;
        final Route route;
        final int width,height;
        final Bitmap coverage,bitmap;
        final Canvas maskCanvas;
        final RectF destination;
        final float[] progress;
        final int[] pixels,palette=new int[2049];
        int[] indices,codes;
        byte[] alpha;
        int count,lastLevel=-1;
        float firstProgress,progressSpan;

        Field(TrioAppearance a,float p0,float p1,float sweep,float start) {
            geometry=a;this.p0=p0;this.p1=p1;this.sweep=sweep;this.start=start;capRight=TrioGeometry.capRight();
            stroke=a.capsule?a.ringStroke*TrioGeometry.CAP_STROKE_RATIO:a.ringStroke;
            route=route(a,p0,p1,sweep,start);
            float pad=stroke/2f+2f;
            left=route.length>0?route.minX-pad:0;top=route.length>0?route.minY-pad:0;
            float w=route.length>0?route.maxX-route.minX+2*pad:1;
            float h=route.length>0?route.maxY-route.minY+2*pad:1;
            scale=Math.min(2.5f,512f/Math.max(w,h));
            width=Math.max(1,(int)Math.ceil(w*scale));height=Math.max(1,(int)Math.ceil(h*scale));
            coverage=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            maskCanvas=new Canvas(coverage);maskCanvas.scale(scale,scale);maskCanvas.translate(-left,-top);
            destination=new RectF(left,top,left+width/scale,top+height/scale);
            progress=new float[width*height];pixels=new int[progress.length];
            mapProgress();
        }
        boolean matches(TrioAppearance a,float from,float to,float degrees,float angle) {
            TrioAppearance g=geometry;
            if(p0!=from||p1!=to||sweep!=degrees||start!=angle||g.capsule!=a.capsule||g.ring!=a.ring
                    ||g.ringStroke!=a.ringStroke||g.splitMode!=a.splitMode)return false;
            if(a.capsule)return capRight==TrioGeometry.capRight();
            return a.splitMode!=1||(g.splitCap==a.splitCap&&g.splitLink==a.splitLink
                &&g.splitLeftBend==a.splitLeftBend&&g.splitRightBend==a.splitRightBend
                &&g.splitLeftDistance==a.splitLeftDistance&&g.splitRightDistance==a.splitRightDistance
                &&g.splitLeftHeight==a.splitLeftHeight&&g.splitRightHeight==a.splitRightHeight
                &&g.splitLeftTop==a.splitLeftTop&&g.splitRightTop==a.splitRightTop
                &&g.splitLeftBottom==a.splitLeftBottom&&g.splitRightBottom==a.splitRightBottom
                &&g.splitLeftY==a.splitLeftY&&g.splitRightY==a.splitRightY);
        }
        private void mapProgress() {
            float[] nearest=new float[progress.length];Arrays.fill(nearest,Float.POSITIVE_INFINITY);
            float pad=stroke/2f+2f;
            // Rasterise only a narrow neighbourhood of each centre-line segment, once per geometry.
            for(Segment s:route.segments) {
                int x0=Math.max(0,(int)Math.floor((Math.min(s.x,s.x+s.dx)-pad-left)*scale));
                int x1=Math.min(width-1,(int)Math.ceil((Math.max(s.x,s.x+s.dx)+pad-left)*scale));
                int y0=Math.max(0,(int)Math.floor((Math.min(s.y,s.y+s.dy)-pad-top)*scale));
                int y1=Math.min(height-1,(int)Math.ceil((Math.max(s.y,s.y+s.dy)+pad-top)*scale));
                float square=s.length*s.length;
                for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++) {
                    float px=left+(x+.5f)/scale-s.x,py=top+(y+.5f)/scale-s.y;
                    float t=(px*s.dx+py*s.dy)/square,clamped=Math.max(0,Math.min(1,t));
                    float dx=px-clamped*s.dx,dy=py-clamped*s.dy,distance=dx*dx+dy*dy;
                    int at=y*width+x;if(distance>=nearest[at])continue;
                    nearest[at]=distance;
                    // Caps follow the same tangent-space gradient as the adjacent body.
                    float projection=t<0&&s.first||t>1&&s.last?t:clamped;
                    progress[at]=(s.offset+projection*s.length)/route.length;
                }
            }
        }
        private void updateCoverage(int level,TrioAppearance a) {
            maskCanvas.drawColor(0,PorterDuff.Mode.CLEAR);
            TrioRenderer.drawChargeFlowMask(maskCanvas,level,a,p0,p1,sweep,start);
            coverage.getPixels(pixels,0,width,0,0,width,height);
            count=0;for(int pixel:pixels)if((pixel>>>24)!=0)count++;
            indices=new int[count];codes=new int[count];alpha=new byte[count];
            float charged=level/100f,cap=stroke/(2*route.length);
            firstProgress=-cap;progressSpan=charged+2*cap;
            Segment begin=route.segments.get(0),end=route.at(charged*route.length);
            float t=Math.max(0,Math.min(1,(charged*route.length-end.offset)/end.length));
            float endX=end.x+t*end.dx,endY=end.y+t*end.dy;
            int n=0;
            for(int at=0;at<pixels.length;at++) {
                int coverageAlpha=pixels[at]>>>24;if(coverageAlpha==0)continue;
                float p=progress[at];
                if(p<0||p>charged) {
                    float x=left+(at%width+.5f)/scale,y=top+(at/width+.5f)/scale;
                    float bx=x-begin.x,by=y-begin.y,ex=x-endX,ey=y-endY;
                    if(bx*bx+by*by<ex*ex+ey*ey)
                        p=(bx*begin.dx+by*begin.dy)/(begin.length*route.length);
                    else p=charged+(ex*end.dx+ey*end.dy)/(end.length*route.length);
                }
                indices[n]=at;alpha[n]=(byte)coverageAlpha;
                codes[n]=Math.max(0,Math.min(2048,Math.round((p-firstProgress)*2048/progressSpan)));n++;
            }
            Arrays.fill(pixels,0);lastLevel=level;
        }
        void draw(Canvas canvas,int level,int role,TrioAppearance a,float phase) {
            if(route.length<=0)return;
            if(lastLevel!=level)updateCoverage(level,a);
            float charged=level/100f,band=Math.min(charged,a.chargeFlowWidth/100f);
            boolean cyclic=route.closed&&level==100;
            if(!cyclic)band*=progressSpan/charged;
            float head=phase*(progressSpan+band)+firstProgress-band;
            int red=(role>>16)&255,green=(role>>8)&255,blue=role&255;
            for(int i=0;i<palette.length;i++) {
                float p=firstProgress+progressSpan*i/2048f;
                if(a.chargeFlowDirection==1)p=charged-p;
                float position;
                if(cyclic) {position=(p-phase)%1f;if(position<0)position+=1f;position/=band;}
                else position=(p-head)/band;
                float glow=position>=0&&position<=1?TrioRenderer.chargeFlowGlow(position,a.chargeFlowGradient):0;
                palette[i]=((red+Math.round((255-red)*glow))<<16)
                        |((green+Math.round((255-green)*glow))<<8)|(blue+Math.round((255-blue)*glow));
            }
            int opacity=role>>>24;
            for(int i=0;i<count;i++)pixels[indices[i]]=(((alpha[i]&255)*opacity+127)/255<<24)|palette[codes[i]];
            bitmap.setPixels(pixels,0,width,0,0,width,height);
            canvas.drawBitmap(bitmap,null,destination,BITMAP_PAINT);
        }
    }
}
