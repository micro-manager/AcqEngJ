package org.micromanager.acqj.internal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.micromanager.acqj.main.AcquisitionEvent;

/**
 * Regression tests for the bug where the focus (Z) stage is driven back to the
 * Z-stack origin between slices.
 *
 * <p>Background: when a Z stack is built so that the focus device ends up in an
 * event's stage-coordinate map (e.g. via
 * {@code AcqEventModules.moveStage(core.getFocusDevice(), ...)}), the focus
 * device name appears in {@code AcquisitionEvent.getStageDeviceNames()}. The
 * "Other stage devices" loop in {@link Engine#prepareHardware} iterates over
 * those names using the <em>first</em> event of a hardware sequence
 * ({@code event.getSequence().get(0)}), so before the fix it called
 * {@code core.setPosition(focusDevice, originPosition)} on every event - moving
 * the focus back to the stack origin. The focus device is supposed to be moved
 * only by the separate {@code startZDrive} method.
 *
 * <p>PR #137 fixes this by skipping the focus device in that loop. These tests
 * exercise {@code prepareHardware} directly (via reflection) against a
 * {@link RecordingCMMCore} and assert that the focus device is never moved by
 * the "Other stage devices" loop, while ordinary (non-focus) stages still are.
 */
public class TestZStackFocusOrigin {

   private static final String FOCUS_DEVICE = "Z";
   private static final String XY_DEVICE = "XY";
   private static final String CAMERA_DEVICE = "Cam";
   private static final String SLM_DEVICE = "";

   private RecordingCMMCore core_;
   private Engine engine_;

   @Before
   public void setUp() throws Exception {
      core_ = new RecordingCMMCore(FOCUS_DEVICE, XY_DEVICE, SLM_DEVICE, CAMERA_DEVICE);
      // Engine is a singleton; force-install our recording core regardless of
      // any Engine created by another test in the same JVM.
      resetEngineSingleton();
      engine_ = new Engine(core_);
      Assert.assertSame("Engine must use the recording core",
            core_, Engine.getCore());
   }

   /**
    * Pre-fix: fails because the focus device is moved to the stack origin (0.0)
    * by the "Other stage devices" loop. Post-fix: passes because the focus
    * device is skipped in that loop.
    */
   @Test
   public void focusDeviceNotMovedByOtherStageLoop() throws Exception {
      // A 3-slice Z stack stored on the focus device as a stage coordinate,
      // exactly as AcqEventModules.moveStage(focusDevice, ...) does.
      AcquisitionEvent sequenceEvent = buildFocusStageSequence(
            new double[] {0.0, 1.0, 2.0});

      invokePrepareHardware(sequenceEvent);

      List<Double> focusMoves = core_.positionsSetFor(FOCUS_DEVICE);
      Assert.assertTrue(
            "The 'Other stage devices' loop must not move the focus device "
                  + "(it is handled by startZDrive). Got moves: " + focusMoves,
            focusMoves.isEmpty());
   }

   /**
    * Guards against the fix being too broad: ordinary (non-focus) stage devices
    * must still be moved by the "Other stage devices" loop.
    */
   @Test
   public void nonFocusStageStillMoved() throws Exception {
      String extraStage = "PiezoX";
      // First slice (origin) of a 3-slice stack; the loop uses sequence.get(0).
      List<AcquisitionEvent> slices = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
         AcquisitionEvent slice = new AcquisitionEvent((org.micromanager.acqj.api.AcquisitionAPI) null);
         slice.setStageCoordinate(FOCUS_DEVICE, i * 1.0);
         slice.setStageCoordinate(extraStage, 10.0 + i);
         slices.add(slice);
      }
      AcquisitionEvent sequenceEvent = new AcquisitionEvent(slices);

      invokePrepareHardware(sequenceEvent);

      // Focus device still skipped...
      Assert.assertTrue("Focus device must not be moved by the loop",
            core_.positionsSetFor(FOCUS_DEVICE).isEmpty());
      // ...but the ordinary stage is moved to its origin (slice 0) value.
      List<Double> extraMoves = core_.positionsSetFor(extraStage);
      Assert.assertEquals("Non-focus stage should be moved exactly once",
            1, extraMoves.size());
      Assert.assertEquals("Non-focus stage should move to its origin position",
            10.0, extraMoves.get(0), 1e-9);
   }

   // ----- helpers -------------------------------------------------------------

   private AcquisitionEvent buildFocusStageSequence(double[] positions) {
      List<AcquisitionEvent> slices = new ArrayList<>();
      for (double pos : positions) {
         AcquisitionEvent slice = new AcquisitionEvent((org.micromanager.acqj.api.AcquisitionAPI) null);
         // Mirrors AcqEventModules.moveStage(focusDevice, ...): the focus
         // position is stored as a stage coordinate, not in zPosition_.
         slice.setStageCoordinate(FOCUS_DEVICE, pos);
         slices.add(slice);
      }
      return new AcquisitionEvent(slices);
   }

   private void invokePrepareHardware(AcquisitionEvent event) throws Exception {
      Method prepareHardware = Engine.class.getDeclaredMethod(
            "prepareHardware", AcquisitionEvent.class, HardwareSequences.class);
      prepareHardware.setAccessible(true);
      try {
         prepareHardware.invoke(engine_, event, new HardwareSequences());
      } catch (java.lang.reflect.InvocationTargetException e) {
         // Surface the real cause for easier debugging.
         Throwable cause = e.getCause();
         if (cause instanceof Exception) {
            throw (Exception) cause;
         }
         throw e;
      }
   }

   private static void resetEngineSingleton() throws Exception {
      EngineTestHarness.resetEngineSingleton();
   }
}
