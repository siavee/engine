// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.renderer;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.Log;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.view.TextureRegistry;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents the rendering responsibilities of a {@code FlutterEngine}.
 *
 * <p>{@code FlutterRenderer} works in tandem with a provided {@link RenderSurface} to paint Flutter
 * pixels to an Android {@code View} hierarchy.
 *
 * <p>{@code FlutterRenderer} manages textures for rendering, and forwards some Java calls to native
 * Flutter code via JNI. The corresponding {@link RenderSurface} provides the Android {@link
 * Surface} that this renderer paints.
 *
 * <p>{@link io.flutter.embedding.android.FlutterSurfaceView} and {@link
 * io.flutter.embedding.android.FlutterTextureView} are implementations of {@link RenderSurface}.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class FlutterRenderer implements TextureRegistry {
  private static final String TAG = "FlutterRenderer";

  @NonNull private final FlutterJNI flutterJNI;
  @NonNull private final AtomicLong nextTextureId = new AtomicLong(0L);
  @Nullable private Surface surface;
  private boolean isDisplayingFlutterUi = false;
  private Handler handler = new Handler();

  @NonNull
  private final FlutterUiDisplayListener flutterUiDisplayListener =
      new FlutterUiDisplayListener() {
        @Override
        public void onFlutterUiDisplayed() {
          isDisplayingFlutterUi = true;
        }

        @Override
        public void onFlutterUiNoLongerDisplayed() {
          isDisplayingFlutterUi = false;
        }
      };

  public FlutterRenderer(@NonNull FlutterJNI flutterJNI) {
    this.flutterJNI = flutterJNI;
    this.flutterJNI.addIsDisplayingFlutterUiListener(flutterUiDisplayListener);
  }

  /**
   * Returns true if this {@code FlutterRenderer} is painting pixels to an Android {@code View}
   * hierarchy, false otherwise.
   */
  public boolean isDisplayingFlutterUi() {
    return isDisplayingFlutterUi;
  }

  /**
   * Adds a listener that is invoked whenever this {@code FlutterRenderer} starts and stops painting
   * pixels to an Android {@code View} hierarchy.
   */
  public void addIsDisplayingFlutterUiListener(@NonNull FlutterUiDisplayListener listener) {
    flutterJNI.addIsDisplayingFlutterUiListener(listener);

    if (isDisplayingFlutterUi) {
      listener.onFlutterUiDisplayed();
    }
  }

  /**
   * Removes a listener added via {@link
   * #addIsDisplayingFlutterUiListener(FlutterUiDisplayListener)}.
   */
  public void removeIsDisplayingFlutterUiListener(@NonNull FlutterUiDisplayListener listener) {
    flutterJNI.removeIsDisplayingFlutterUiListener(listener);
  }

  // ------ START TextureRegistry IMPLEMENTATION -----
  /**
   * Creates and returns a new {@link SurfaceTexture} that is also made available to Flutter code.
   */
  @Override
  public SurfaceTextureEntry createSurfaceTexture() {
    Log.v(TAG, "Creating a SurfaceTexture.");
    final SurfaceTexture surfaceTexture = new SurfaceTexture(0);
    surfaceTexture.detachFromGLContext();
    final SurfaceTextureRegistryEntry entry =
        new SurfaceTextureRegistryEntry(nextTextureId.getAndIncrement(), surfaceTexture);
    Log.v(TAG, "New SurfaceTexture ID: " + entry.id());
    registerTexture(entry.id(), entry.textureWrapper());
    return entry;
  }

  final class SurfaceTextureRegistryEntry implements TextureRegistry.SurfaceTextureEntry {
    private final long id;
    @NonNull private final SurfaceTextureWrapper textureWrapper;
    private boolean released;

    SurfaceTextureRegistryEntry(long id, @NonNull SurfaceTexture surfaceTexture) {
      this.id = id;
      this.textureWrapper = new SurfaceTextureWrapper(surfaceTexture);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // The callback relies on being executed on the UI thread (unsynchronised read of
        // mNativeView
        // and also the engine code check for platform thread in
        // Shell::OnPlatformViewMarkTextureFrameAvailable),
        // so we explicitly pass a Handler for the current thread.
        this.surfaceTexture().setOnFrameAvailableListener(onFrameListener, new Handler());
      } else {
        // Android documentation states that the listener can be called on an arbitrary thread.
        // But in practice, versions of Android that predate the newer API will call the listener
        // on the thread where the SurfaceTexture was constructed.
        this.surfaceTexture().setOnFrameAvailableListener(onFrameListener);
      }
    }

    private SurfaceTexture.OnFrameAvailableListener onFrameListener =
        new SurfaceTexture.OnFrameAvailableListener() {
          @Override
          public void onFrameAvailable(@NonNull SurfaceTexture texture) {
            if (released || !flutterJNI.isAttached()) {
              // Even though we make sure to unregister the callback before releasing, as of
              // Android O, SurfaceTexture has a data race when accessing the callback, so the
              // callback may still be called by a stale reference after released==true and
              // mNativeView==null.
              return;
            }
            markTextureFrameAvailable(id);
          }
        };

    @NonNull
    public SurfaceTextureWrapper textureWrapper() {
      return textureWrapper;
    }

    @Override
    @NonNull
    public SurfaceTexture surfaceTexture() {
      return textureWrapper.surfaceTexture();
    }

    @Override
    public long id() {
      return id;
    }

    @Override
    public void release() {
      if (released) {
        return;
      }
      Log.v(TAG, "Releasing a SurfaceTexture (" + id + ").");
      textureWrapper.release();
      unregisterTexture(id);
      released = true;
    }

    @Override
    protected void finalize() throws Throwable {
      try {
        if (released) {
          return;
        }

        handler.post(new SurfaceTextureFinalizerRunnable(id, flutterJNI));
      } finally {
        super.finalize();
      }
    }
  }

  static final class SurfaceTextureFinalizerRunnable implements Runnable {
    private final long id;
    private final FlutterJNI flutterJNI;

    SurfaceTextureFinalizerRunnable(long id, @NonNull FlutterJNI flutterJNI) {
      this.id = id;
      this.flutterJNI = flutterJNI;
    }

    @Override
    public void run() {
      if (!flutterJNI.isAttached()) {
        return;
      }
      Log.v(TAG, "Releasing a SurfaceTexture (" + id + ").");
      flutterJNI.unregisterTexture(id);
    }
  }
  // ------ END TextureRegistry IMPLEMENTATION ----

  /**
   * Notifies Flutter that the given {@code surface} was created and is available for Flutter
   * rendering.
   *
   * <p>See {@link android.view.SurfaceHolder.Callback} and {@link
   * android.view.TextureView.SurfaceTextureListener}
   */
  public void startRenderingToSurface(@NonNull Surface surface) {
    if (this.surface != null) {
      stopRenderingToSurface();
    }

    this.surface = surface;

    flutterJNI.onSurfaceCreated(surface);
  }

  /**
   * Swaps the {@link Surface} used to render the current frame.
   *
   * <p>In hybrid composition, the root surfaces changes from {@link
   * android.view.SurfaceHolder#getSurface()} to {@link android.media.ImageReader#getSurface()} when
   * a platform view is in the current frame.
   */
  public void swapSurface(@NonNull Surface surface) {
    this.surface = surface;
    flutterJNI.onSurfaceWindowChanged(surface);
  }

  /**
   * Notifies Flutter that a {@code surface} previously registered with {@link
   * #startRenderingToSurface(Surface)} has changed size to the given {@code width} and {@code
   * height}.
   *
   * <p>See {@link android.view.SurfaceHolder.Callback} and {@link
   * android.view.TextureView.SurfaceTextureListener}
   */
  public void surfaceChanged(int width, int height) {
    flutterJNI.onSurfaceChanged(width, height);
  }

  /**
   * Notifies Flutter that a {@code surface} previously registered with {@link
   * #startRenderingToSurface(Surface)} has been destroyed and needs to be released and cleaned up
   * on the Flutter side.
   *
   * <p>See {@link android.view.SurfaceHolder.Callback} and {@link
   * android.view.TextureView.SurfaceTextureListener}
   */
  public void stopRenderingToSurface() {
    flutterJNI.onSurfaceDestroyed();

    surface = null;

    // TODO(mattcarroll): the source of truth for this call should be FlutterJNI, which is where
    // the call to onFlutterUiDisplayed() comes from. However, no such native callback exists yet,
    // so until the engine and FlutterJNI are configured to call us back when rendering stops,
    // we will manually monitor that change here.
    if (isDisplayingFlutterUi) {
      flutterUiDisplayListener.onFlutterUiNoLongerDisplayed();
    }

    isDisplayingFlutterUi = false;
  }

  /**
   * Notifies Flutter that the viewport metrics, e.g. window height and width, have changed.
   *
   * <p>If the width, height, or devicePixelRatio are less than or equal to 0, this update is
   * ignored.
   *
   * @param viewportMetrics The metrics to send to the Dart application.
   */
  public void setViewportMetrics(@NonNull ViewportMetrics viewportMetrics) {
    // We might get called with just the DPR if width/height aren't available yet.
    // Just ignore, as it will get called again when width/height are set.
    if (!viewportMetrics.validate()) {
      return;
    }
    Log.v(
        TAG,
        "Setting viewport metrics\n"
            + "Size: "
            + viewportMetrics.width
            + " x "
            + viewportMetrics.height
            + "\n"
            + "Padding - L: "
            + viewportMetrics.viewPaddingLeft
            + ", T: "
            + viewportMetrics.viewPaddingTop
            + ", R: "
            + viewportMetrics.viewPaddingRight
            + ", B: "
            + viewportMetrics.viewPaddingBottom
            + "\n"
            + "Insets - L: "
            + viewportMetrics.viewInsetLeft
            + ", T: "
            + viewportMetrics.viewInsetTop
            + ", R: "
            + viewportMetrics.viewInsetRight
            + ", B: "
            + viewportMetrics.viewInsetBottom
            + "\n"
            + "System Gesture Insets - L: "
            + viewportMetrics.systemGestureInsetLeft
            + ", T: "
            + viewportMetrics.systemGestureInsetTop
            + ", R: "
            + viewportMetrics.systemGestureInsetRight
            + ", B: "
            + viewportMetrics.viewInsetBottom);

    flutterJNI.setViewportMetrics(
        viewportMetrics.devicePixelRatio,
        viewportMetrics.width,
        viewportMetrics.height,
        viewportMetrics.viewPaddingTop,
        viewportMetrics.viewPaddingRight,
        viewportMetrics.viewPaddingBottom,
        viewportMetrics.viewPaddingLeft,
        viewportMetrics.viewInsetTop,
        viewportMetrics.viewInsetRight,
        viewportMetrics.viewInsetBottom,
        viewportMetrics.viewInsetLeft,
        viewportMetrics.systemGestureInsetTop,
        viewportMetrics.systemGestureInsetRight,
        viewportMetrics.systemGestureInsetBottom,
        viewportMetrics.systemGestureInsetLeft,
        viewportMetrics.physicalTouchSlop);
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  // TODO(mattcarroll): determine if this is nullable or nonnull
  public Bitmap getBitmap() {
    return flutterJNI.getBitmap();
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  public void dispatchPointerDataPacket(@NonNull ByteBuffer buffer, int position) {
    flutterJNI.dispatchPointerDataPacket(buffer, position);
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  private void registerTexture(long textureId, @NonNull SurfaceTextureWrapper textureWrapper) {
    flutterJNI.registerTexture(textureId, textureWrapper);
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  private void markTextureFrameAvailable(long textureId) {
    flutterJNI.markTextureFrameAvailable(textureId);
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  private void unregisterTexture(long textureId) {
    flutterJNI.unregisterTexture(textureId);
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  public boolean isSoftwareRenderingEnabled() {
    return flutterJNI.getIsSoftwareRenderingEnabled();
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  public void setAccessibilityFeatures(int flags) {
    flutterJNI.setAccessibilityFeatures(flags);
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  public void setSemanticsEnabled(boolean enabled) {
    flutterJNI.setSemanticsEnabled(enabled);
  }

  // TODO(mattcarroll): describe the native behavior that this invokes
  public void dispatchSemanticsAction(
      int id, int action, @Nullable ByteBuffer args, int argsPosition) {
    flutterJNI.dispatchSemanticsAction(id, action, args, argsPosition);
  }

  /**
   * Mutable data structure that holds all viewport metrics properties that Flutter cares about.
   *
   * <p>All distance measurements, e.g., width, height, padding, viewInsets, are measured in device
   * pixels, not logical pixels.
   */
  public static final class ViewportMetrics {
    /** A value that indicates the setting has not been set. */
    public static final int unsetValue = -1;

    public float devicePixelRatio = 1.0f;
    public int width = 0;
    public int height = 0;
    public int viewPaddingTop = 0;
    public int viewPaddingRight = 0;
    public int viewPaddingBottom = 0;
    public int viewPaddingLeft = 0;
    public int viewInsetTop = 0;
    public int viewInsetRight = 0;
    public int viewInsetBottom = 0;
    public int viewInsetLeft = 0;
    public int systemGestureInsetTop = 0;
    public int systemGestureInsetRight = 0;
    public int systemGestureInsetBottom = 0;
    public int systemGestureInsetLeft = 0;
    public int physicalTouchSlop = unsetValue;

    /**
     * Whether this instance contains valid metrics for the Flutter application.
     *
     * @return True if width, height, and devicePixelRatio are > 0; false otherwise.
     */
    boolean validate() {
      return width > 0 && height > 0 && devicePixelRatio > 0;
    }
  }
}
