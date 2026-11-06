package android.graphics;

/** Software ARGB bitmap matching the subset used by the production flow renderer. */
public final class Bitmap {
    public enum Config { ARGB_8888 }
    final java.awt.image.BufferedImage image;
    private Bitmap(int w,int h) {image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);}
    public static Bitmap createBitmap(int w,int h,Config config) {return new Bitmap(w,h);}
    public void getPixels(int[] pixels,int offset,int stride,int x,int y,int w,int h) {
        image.getRGB(x,y,w,h,pixels,offset,stride);
    }
    public void setPixels(int[] pixels,int offset,int stride,int x,int y,int w,int h) {
        image.setRGB(x,y,w,h,pixels,offset,stride);
    }
}
