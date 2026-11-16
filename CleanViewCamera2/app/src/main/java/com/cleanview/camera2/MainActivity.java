package com.cleanview.camera2;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CAMERA=42, PORT=8080;
    private PreviewView previewView;
    private TextView statusText,urlText;
    private ImageView qrImage;
    private Button streamButton;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector selector=CameraSelector.DEFAULT_BACK_CAMERA;
    private volatile byte[] latestJpeg;
    private volatile long frameNumber=0;
    private StreamServer server;
    private boolean streaming=false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        cameraExecutor=Executors.newSingleThreadExecutor();
        buildUi();
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera();
        else ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA);
    }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(16,12,16,12); root.setBackgroundColor(0xFF101820);
        TextView title=new TextView(this); title.setText("CleanView Camera 2"); title.setTextColor(-1); title.setTextSize(24); root.addView(title);
        statusText=new TextView(this); statusText.setText("Allow camera permission to begin."); statusText.setTextColor(0xFFE0E0E0); statusText.setPadding(0,8,0,8); root.addView(statusText);
        previewView=new PreviewView(this); previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView,new LinearLayout.LayoutParams(-1,0,1f));
        streamButton=new Button(this); streamButton.setText("START STREAMING"); streamButton.setOnClickListener(v->toggleStreaming()); root.addView(streamButton);
        Button flip=new Button(this); flip.setText("SWITCH FRONT / REAR CAMERA"); flip.setOnClickListener(v->switchCamera()); root.addView(flip);
        urlText=new TextView(this); urlText.setTextColor(-1); urlText.setTextSize(16); urlText.setGravity(Gravity.CENTER); urlText.setPadding(0,8,0,4); root.addView(urlText);
        qrImage=new ImageView(this); qrImage.setAdjustViewBounds(true); qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(190,190); qp.gravity=Gravity.CENTER_HORIZONTAL; root.addView(qrImage,qp);
        TextView hint=new TextView(this); hint.setText("Connect the receiving device to the same Wi-Fi or phone hotspot. Open the address above in its browser. The receiver shows video only."); hint.setTextColor(0xFFB0BEC5); hint.setTextSize(12); root.addView(hint);
        setContentView(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f=ProcessCameraProvider.getInstance(this);
        f.addListener(()->{try{cameraProvider=f.get();bindCamera();}catch(Exception e){statusText.setText("Camera startup failed: "+e.getMessage());}},ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if(cameraProvider==null)return;
        Preview preview=new Preview.Builder().build(); preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageAnalysis analysis=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(new android.util.Size(1280,720)).build();
        analysis.setAnalyzer(cameraExecutor,image->{
            try { byte[] jpg=toJpeg(image,70); if(jpg!=null){latestJpeg=jpg; synchronized(this){frameNumber++;notifyAll();}} }
            catch(Exception ignored){} finally{image.close();}
        });
        try{cameraProvider.unbindAll();cameraProvider.bindToLifecycle(this,selector,preview,analysis);statusText.setText("Camera ready. Tap START STREAMING.");}
        catch(Exception e){statusText.setText("Unable to start camera: "+e.getMessage());}
    }

    private byte[] toJpeg(ImageProxy image,int quality) {
        int w=image.getWidth(),h=image.getHeight(); ImageProxy.PlaneProxy[] p=image.getPlanes();
        byte[] nv21=new byte[w*h*3/2]; ByteBuffer y=p[0].getBuffer(); int yRow=p[0].getRowStride(),yPix=p[0].getPixelStride(),k=0;
        for(int r=0;r<h;r++)for(int c=0;c<w;c++)nv21[k++]=y.get(r*yRow+c*yPix);
        ByteBuffer v=p[2].getBuffer(),u=p[1].getBuffer(); int vr=p[2].getRowStride(),ur=p[1].getRowStride(),vp=p[2].getPixelStride(),up=p[1].getPixelStride();
        for(int r=0;r<h/2;r++)for(int c=0;c<w/2;c++){nv21[k++]=v.get(r*vr+c*vp);nv21[k++]=u.get(r*ur+c*up);}
        YuvImage yuv=new YuvImage(nv21,ImageFormat.NV21,w,h,null); ByteArrayOutputStream out=new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0,0,w,h),quality,out); return out.toByteArray();
    }

    private void toggleStreaming() {
        if(streaming){stopStreaming();return;}
        try{
            server=new StreamServer(PORT); server.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false);
            String ip=getLocalIp();
            if(ip==null){server.stop();server=null;statusText.setText("No local network IP found. Connect Wi-Fi or enable phone hotspot.");return;}
            String url="http://"+ip+":"+PORT; urlText.setText(url); qrImage.setImageBitmap(qr(url));
            streamButton.setText("STOP STREAMING"); statusText.setText("Streaming active. Open the address on a device on the same network."); streaming=true;
        }catch(Exception e){statusText.setText("Could not start server: "+e.getMessage());}
    }
    private void stopStreaming(){if(server!=null){server.stop();server=null;}streaming=false;if(streamButton!=null)streamButton.setText("START STREAMING");if(urlText!=null)urlText.setText("");if(qrImage!=null)qrImage.setImageDrawable(null);if(statusText!=null)statusText.setText("Streaming stopped.");}
    private void switchCamera(){selector=selector==CameraSelector.DEFAULT_BACK_CAMERA?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;bindCamera();}

    private String getLocalIp(){
        // Prefer Wi-Fi / hotspot interfaces and private LAN addresses over cellular interfaces.
        String bestIp=null; int bestScore=-1;
        try{
            for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){
                if(!ni.isUp()||ni.isLoopback()) continue;
                String ifName=ni.getName().toLowerCase(java.util.Locale.US);
                int interfaceScore=(ifName.contains("wlan")||ifName.contains("wifi")||ifName.contains("ap"))?10:0;
                for(InetAddress a:Collections.list(ni.getInetAddresses())){
                    if(!(a instanceof Inet4Address)||a.isLoopbackAddress()) continue;
                    String ip=a.getHostAddress();
                    if(ip==null||ip.startsWith("169.254.")) continue;
                    int score=interfaceScore;
                    if(ip.startsWith("192.168.")||ip.startsWith("10.")) score+=5;
                    else if(ip.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")) score+=4;
                    if(score>bestScore){bestScore=score;bestIp=ip;}
                }
            }
        }catch(Exception ignored){}
        return bestIp;
    }
    private Bitmap qr(String value){try{BitMatrix m=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,420,420);Bitmap b=Bitmap.createBitmap(420,420,Bitmap.Config.ARGB_8888);for(int x=0;x<420;x++)for(int y=0;y<420;y++)b.setPixel(x,y,m.get(x,y)?0xFF000000:0xFFFFFFFF);return b;}catch(Exception e){return null;}}

    @Override public void onRequestPermissionsResult(int r,@NonNull String[] p,@NonNull int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQUEST_CAMERA&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCamera();else statusText.setText("Camera permission is required.");}
    @Override protected void onDestroy(){stopStreaming();if(cameraProvider!=null)cameraProvider.unbindAll();cameraExecutor.shutdown();super.onDestroy();}

    private class StreamServer extends NanoHTTPD {
        StreamServer(int port){super(port);}
        @Override public Response serve(IHTTPSession s){
            if("/stream.mjpg".equals(s.getUri()))return newChunkedResponse(Response.Status.OK,"multipart/x-mixed-replace; boundary=frame",new MjpegInput());
            if("/snapshot.jpg".equals(s.getUri())){byte[] f=latestJpeg;if(f==null)return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,"text/plain","Camera not ready");return newFixedLengthResponse(Response.Status.OK,"image/jpeg",new ByteArrayInputStream(f),f.length);}
            String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>CleanView Live</title><style>*{box-sizing:border-box}body{margin:0;background:#000}img{width:100vw;height:100vh;object-fit:contain;display:block}button{position:fixed;right:12px;top:12px;padding:12px;border:0;border-radius:8px;background:#222;color:white;opacity:.8}</style></head><body><img src='/stream.mjpg'><button onclick='document.documentElement.requestFullscreen?.()'>Full screen</button></body></html>";
            return newFixedLengthResponse(Response.Status.OK,"text/html",html);
        }
    }

    private class MjpegInput extends InputStream {
        byte[] current=new byte[0]; int offset=0; long last=-1;
        @Override public int read()throws IOException{byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int off,int len)throws IOException{
            if(len==0)return 0;
            if(offset>=current.length){
                byte[] jpg;
                synchronized(MainActivity.this){
                    while(frameNumber==last||latestJpeg==null){try{MainActivity.this.wait(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
                    jpg=latestJpeg;last=frameNumber;
                }
                byte[] head=("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "+jpg.length+"\r\n\r\n").getBytes("US-ASCII");
                current=new byte[head.length+jpg.length+2];System.arraycopy(head,0,current,0,head.length);System.arraycopy(jpg,0,current,head.length,jpg.length);current[current.length-2]=13;current[current.length-1]=10;offset=0;
            }
            int n=Math.min(len,current.length-offset);System.arraycopy(current,offset,b,off,n);offset+=n;return n;
        }
    }
}
