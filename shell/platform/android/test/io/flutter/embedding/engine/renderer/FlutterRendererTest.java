package io.flutter.embedding.engine.renderer;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.Surface;
import io.flutter.embedding.engine.FlutterJNI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class FlutterRendererTest {

  private FlutterJNI fakeFlutterJNI;
  private Surface fakeSurface;

  @Before
  public void setup() {
    fakeFlutterJNI = mock(FlutterJNI.class);
    fakeSurface = mock(Surface.class);
  }

  @Test
  public void itForwardsSurfaceCreationNotificationToFlutterJNI() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    // Execute the behavior under test.
    flutterRenderer.startRenderingToSurface(fakeSurface);

    // Verify the behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceCreated(eq(fakeSurface));
  }

  @Test
  public void itForwardsSurfaceChangeNotificationToFlutterJNI() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    flutterRenderer.startRenderingToSurface(fakeSurface);

    // Execute the behavior under test.
    flutterRenderer.surfaceChanged(100, 50);

    // Verify the behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceChanged(eq(100), eq(50));
  }

  @Test
  public void itForwardsSurfaceDestructionNotificationToFlutterJNI() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    flutterRenderer.startRenderingToSurface(fakeSurface);

    // Execute the behavior under test.
    flutterRenderer.stopRenderingToSurface();

    // Verify the behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceDestroyed();
  }

  @Test
  public void itStopsRenderingToOneSurfaceBeforeRenderingToANewSurface() {
    // Setup the test.
    Surface fakeSurface2 = mock(Surface.class);
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    flutterRenderer.startRenderingToSurface(fakeSurface);

    // Execute behavior under test.
    flutterRenderer.startRenderingToSurface(fakeSurface2);

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceDestroyed(); // notification of 1st surface's removal.
  }

  @Test
  public void itStopsRenderingToSurfaceWhenRequested() {
    // Setup the test.
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    flutterRenderer.startRenderingToSurface(fakeSurface);

    // Execute the behavior under test.
    flutterRenderer.stopRenderingToSurface();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceDestroyed();
  }

  @Test
  public void itStopsSurfaceTextureCallbackWhenDetached() {
    // Setup the test.
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    fakeFlutterJNI.detachFromNativeAndReleaseResources();

    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();

    flutterRenderer.startRenderingToSurface(fakeSurface);

    // Execute the behavior under test.
    flutterRenderer.stopRenderingToSurface();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(0)).markTextureFrameAvailable(eq(entry.id()));
  }

  @Test
  public void itUnregistersTextureWhenSurfaceTextureFinalized() {
    // Setup the test.
    FlutterJNI fakeFlutterJNI = mock(FlutterJNI.class);
    when(fakeFlutterJNI.isAttached()).thenReturn(true);
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    fakeFlutterJNI.detachFromNativeAndReleaseResources();

    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();
    long id = entry.id();

    flutterRenderer.startRenderingToSurface(fakeSurface);

    // Execute the behavior under test.
    runFinalization(entry);

    shadowOf(Looper.getMainLooper()).idle();

    flutterRenderer.stopRenderingToSurface();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(1)).unregisterTexture(eq(id));
  }

  @Test
  public void itStopsUnregisteringTextureWhenDetached() {
    // Setup the test.
    FlutterJNI fakeFlutterJNI = mock(FlutterJNI.class);
    when(fakeFlutterJNI.isAttached()).thenReturn(false);
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    fakeFlutterJNI.detachFromNativeAndReleaseResources();

    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();
    long id = entry.id();

    flutterRenderer.startRenderingToSurface(fakeSurface);

    flutterRenderer.stopRenderingToSurface();

    // Execute the behavior under test.
    runFinalization(entry);

    shadowOf(Looper.getMainLooper()).idle();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(0)).unregisterTexture(eq(id));
  }

  void runFinalization(FlutterRenderer.SurfaceTextureRegistryEntry entry) {
    CountDownLatch latch = new CountDownLatch(1);
    Thread fakeFinalizer =
        new Thread(
            new Runnable() {
              public void run() {
                try {
                  entry.finalize();
                  latch.countDown();
                } catch (Throwable e) {
                  // do nothing
                }
              }
            });
    fakeFinalizer.start();
    try {
      latch.await(5L, TimeUnit.SECONDS);
    } catch (Throwable e) {
      // do nothing
    }
  }
}
