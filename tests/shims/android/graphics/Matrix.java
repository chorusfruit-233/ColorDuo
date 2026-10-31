/* SPDX-License-Identifier: GPL-3.0-only */
package android.graphics;
public final class Matrix {
    private final java.awt.geom.AffineTransform value;
    public Matrix(java.awt.geom.AffineTransform value){this.value=new java.awt.geom.AffineTransform(value);}
    public void getValues(float[] out){
        out[0]=(float)value.getScaleX();out[1]=(float)value.getShearX();out[2]=(float)value.getTranslateX();
        out[3]=(float)value.getShearY();out[4]=(float)value.getScaleY();out[5]=(float)value.getTranslateY();
        out[6]=0;out[7]=0;out[8]=1;
    }
}
