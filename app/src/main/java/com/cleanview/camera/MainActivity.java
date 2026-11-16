package com.cleanview.camera;

import android.Manifest;
import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;

import java.util.*;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private TextureView phonePreview;
    private TextView status;
    private Button connectButton, switchButton;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId;
    private boolean frontCamera = false;
    private Surface phoneSurface;
    private Surface tvSurface;
    private CleanPresentation presentation;
    private DisplayManager displayManager;
    private final int REQUEST_CAMERA = 101;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        displayManager = (DisplayManager)getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, null);
        startCameraThread();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            phonePreview.setSurfaceTextureListener(this);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 18);
        root.setBackgroundColor(0xFF101820);

        TextView heading = new TextView(this);
        heading.setText("CleanView Camera");
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(23);
        root.addView(heading);

        status = new TextView(this);
        status.setText("Connect your TV using Smart View, then tap Find TV.");
        status.setTextColor(0xFFE0E0E0);
        status.setPadding(0, 12, 0, 12);
        root.addView(status);

        phonePreview = new TextureView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(phonePreview, previewParams);
        phonePreview.setSurfaceTextureListener(this);

        connectButton = new Button(this);
        connectButton.setText("Find TV / Refresh display");
        connectButton.setOnClickListener(v -> findPresentationDisplay());
        root.addView(connectButton);

        switchButton = new Button(this);
        switchButton.setText("Switch front / rear camera");
        switchButton.setOnClickListener(v -> switchCamera());
        root.addView(switchButton);

        TextView note = new TextView(this);
        note.setText("The TV receives only the camera picture. Camera controls remain on this phone. Keep this app open while presenting.");
        note.setTextColor(0xFFB0BEC5);
        note.setPadding(0, 8, 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CameraBackground");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            phonePreview.setSurfaceTextureListener(this);
        } else {
            status.setText("Camera permission is required.");
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) { closeCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture t) { }

    private void findPresentationDisplay() {
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            status.setText("No separate presentation display detected. Your Smart View connection may be mirroring-only. This app cannot hide controls in mirroring mode.");
            return;
        }
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Exception ignored) {}
            presentation = null;
        }
        presentation = new CleanPresentation(this, displays[0]);
        presentation.show();
        status.setText("TV display found: " + displays[0].getName() + ". Waiting for camera preview surface…");
        recreateSessionWhenReady();
    }

    private final DisplayListener displayListener = new DisplayListener() {
        @Override public void onDisplayAdded(int id) { runOnUiThread(() -> findPresentationDisplay()); }
        @Override public void onDisplayRemoved(int id) {
            runOnUiThread(() -> {
                if (presentation != null) { try { presentation.dismiss(); } catch (Exception ignored) {} }
                presentation = null;
                tvSurface = null;
                recreateSessionWhenReady();
                status.setText("TV disconnected. Reconnect using Smart View.");
            });
        }
        @Override public void onDisplayChanged(int id) { }
    };

    private void openCamera() {
        if (camera != null) return;
        try {
            CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            cameraId = chooseCamera(manager);
            if (cameraId == null) { status.setText("No suitable camera found."); return; }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    camera = c;
                    status.setText("Camera ready. Connect TV and tap Find TV.");
                    recreateSessionWhenReady();
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); camera = null; }
                @Override public void onError(CameraDevice c, int error) {
                    c.close(); camera = null;
                    status.setText("Camera error: " + error);
                }
            }, cameraHandler);
        } catch (Exception e) {
            status.setText("Unable to open camera: " + e.getMessage());
        }
    }

    private String chooseCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == (frontCamera ?
                    CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) return id;
        }
        return manager.getCameraIdList().length > 0 ? manager.getCameraIdList()[0] : null;
    }

    private void switchCamera() {
        frontCamera = !frontCamera;
        closeCamera();
        openCamera();
    }

    private void recreateSessionWhenReady() {
        if (camera == null || !phonePreview.isAvailable()) return;
        SurfaceTexture texture = phonePreview.getSurfaceTexture();
        if (texture == null) return;
        texture.setDefaultBufferSize(phonePreview.getWidth(), phonePreview.getHeight());
        phoneSurface = new Surface(texture);

        ArrayList<Surface> outputs = new ArrayList<>();
        outputs.add(phoneSurface);
        if (presentation != null && presentation.getPreviewSurface() != null &&
                presentation.getPreviewSurface().isValid()) {
            tvSurface = presentation.getPreviewSurface();
            outputs.add(tvSurface);
        } else {
            tvSurface = null;
        }

        try {
            camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (camera == null) { s.close(); return; }
                    if (session != null) session.close();
                    session = s;
                    try {
                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(phoneSurface);
                        if (tvSurface != null) request.addTarget(tvSurface);
                        request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        request.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                        runOnUiThread(() -> status.setText(tvSurface == null ?
                                "Phone preview active. TV is not in separate-display mode." :
                                "Live camera preview is being shown on the TV without controls."));
                    } catch (Exception e) {
                        runOnUiThread(() -> status.setText("Preview error: " + e.getMessage()));
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    runOnUiThread(() -> status.setText("Camera could not configure both displays. Try reconnecting the TV."));
                }
            }, cameraHandler);
        } catch (Exception e) {
            status.setText("Unable to start preview: " + e.getMessage());
        }
    }

    private void closeCamera() {
        try { if (session != null) { session.close(); session = null; } } catch (Exception ignored) {}
        try { if (camera != null) { camera.close(); camera = null; } } catch (Exception ignored) {}
        try { if (phoneSurface != null) { phoneSurface.release(); phoneSurface = null; } } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        if (cameraThread == null || !cameraThread.isAlive()) startCameraThread();
        if (phonePreview != null && phonePreview.isAvailable()
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        displayManager.unregisterDisplayListener(displayListener);
        if (presentation != null) { try { presentation.dismiss(); } catch (Exception ignored) {} }
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; }
        super.onDestroy();
    }

    public class CleanPresentation extends Presentation {
        private SurfaceView surfaceView;
        private Surface previewSurface;
        public CleanPresentation(Context context, Display display) { super(context, display); }
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            surfaceView = new SurfaceView(getContext());
            surfaceView.setBackgroundColor(0xFF000000);
            setContentView(surfaceView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    previewSurface = holder.getSurface();
                    recreateSessionWhenReady();
                }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    previewSurface = holder.getSurface();
                    recreateSessionWhenReady();
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    previewSurface = null;
                    recreateSessionWhenReady();
                }
            });
        }
        public Surface getPreviewSurface() { return previewSurface; }
    }
}
