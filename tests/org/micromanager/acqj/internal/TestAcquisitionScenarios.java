package org.micromanager.acqj.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.micromanager.acqj.api.AcquisitionAPI;
import org.micromanager.acqj.api.AcquisitionHook;
import org.micromanager.acqj.example.BlackHoleDataSink;
import org.micromanager.acqj.main.Acquisition;
import org.micromanager.acqj.main.AcquisitionEvent;
import org.micromanager.acqj.util.AcqEventModules;
import org.micromanager.acqj.util.AcquisitionEventIterator;
import org.micromanager.acqj.util.ChannelSetting;
import org.micromanager.acqj.util.xytiling.XYStagePosition;

/**
 * End-to-end-ish tests that generate real acquisition event streams with
 * {@link AcqEventModules} and drive them through the real {@link Engine}
 * hardware-control code (via {@link EngineTestHarness} and
 * {@link RecordingCMMCore}). They assert on the resulting hardware command
 * stream for several common acquisition shapes.
 *
 * <p>None of these require the native MMCoreJ library.
 */
public class TestAcquisitionScenarios {

   private static final String GROUP = "Channel";
   private static final String FOCUS = "Z";
   private static final String XY = "XY";
   private static final String CAM = "Cam";

   private RecordingCMMCore core_;
   private Acquisition acq_;
   private EngineTestHarness harness_;

   @Before
   public void setUp() throws Exception {
      core_ = new RecordingCMMCore(FOCUS, XY, "", CAM);
      // initialize=false avoids makeSummaryMD(), which needs the native core.
      acq_ = new Acquisition(new BlackHoleDataSink(), false);
      harness_ = new EngineTestHarness(core_, acq_);
   }

   private AcquisitionEvent root() {
      return new AcquisitionEvent((AcquisitionAPI) acq_);
   }

   private static ChannelSetting channel(String name, double exposure) {
      return new ChannelSetting(GROUP, name, exposure, 0.0);
   }

   /** Apply a list of module functions to a root event (channels/z/t/positions). */
   private Iterator<AcquisitionEvent> stream(
         List<Function<AcquisitionEvent, Iterator<AcquisitionEvent>>> modules) {
      return new AcquisitionEventIterator(root(), modules);
   }

   // ---------------------------------------------------------------------------
   // Scenario 1: 3 channels, only 2 of them run a z stack.
   // ---------------------------------------------------------------------------
   @Test
   public void threeChannelsOnlyTwoWithZStack() throws Exception {
      // Channels DAPI and FITC get a 4-slice z stack; brightfield BF is a single
      // snap with no z stack. Build the two sub-streams and concatenate them.
      List<ChannelSetting> stackChannels =
            Arrays.asList(channel("DAPI", 10.0), channel("FITC", 20.0));
      List<AcquisitionEvent> events = new ArrayList<>();

      // DAPI + FITC, each with a 4-slice z stack (channels outer, z inner).
      Iterator<AcquisitionEvent> stackStream = stream(Arrays.asList(
            AcqEventModules.channels(stackChannels),
            AcqEventModules.zStack(0, 4, 1.0, 0.0)));
      stackStream.forEachRemaining(events::add);

      // BF: single channel, no z stack.
      Iterator<AcquisitionEvent> bfStream = stream(Collections.singletonList(
            AcqEventModules.channels(Collections.singletonList(channel("BF", 5.0)))));
      bfStream.forEachRemaining(events::add);

      harness_.run(events.iterator());

      // Focus moved for every z-stack slice: 2 channels * 4 slices = 8 moves,
      // to z = 0,1,2,3 within each channel. BF has no z so adds no focus move
      // (its z position is null).
      List<Double> zMoves = core_.positionsSetFor(FOCUS);
      Assert.assertEquals("z stack channels should produce 8 focus moves",
            8, zMoves.size());
      Assert.assertEquals(Arrays.asList(0.0, 1.0, 2.0, 3.0, 0.0, 1.0, 2.0, 3.0),
            zMoves);

      // Three distinct channels are configured, each set once (the engine only
      // calls setConfig when the channel changes).
      Assert.assertEquals(1, core_.countCommands("setConfig " + GROUP + " DAPI"));
      Assert.assertEquals(1, core_.countCommands("setConfig " + GROUP + " FITC"));
      Assert.assertEquals(1, core_.countCommands("setConfig " + GROUP + " BF"));
   }

   // ---------------------------------------------------------------------------
   // Scenario 2: multiple XY positions x 2 channels x hardware-sequenced z stack.
   // ---------------------------------------------------------------------------
   @Test
   public void multiPositionTwoChannelsSequencedZStack() throws Exception {
      // The z stage is sequenceable, so each (position, channel) z stack of 10
      // steps is merged into ONE hardware-sequenced event.
      //
      // NOTE: we assert on the merge/sequencing DECISION only (via merge()), not
      // on execution. Executing a hardware-sequenced event requires the engine to
      // build a native mmcorej.DoubleVector (a JNI call in startZDrive), which is
      // not available without the native MMCoreJ library. The grouping and
      // axis-sequencing decision is the engine logic worth testing here.
      core_.withStageSequenceable(true).withSequenceMaxLength(100);

      List<XYStagePosition> positions = Arrays.asList(
            xyPosition(100.0, 200.0, 0, 0),
            xyPosition(300.0, 400.0, 0, 1));
      List<ChannelSetting> channels =
            Arrays.asList(channel("DAPI", 10.0), channel("FITC", 20.0));

      Iterator<AcquisitionEvent> events = stream(Arrays.asList(
            AcqEventModules.positions(positions),
            AcqEventModules.channels(channels),
            AcqEventModules.zStack(0, 10, 0.5, 0.0)));

      List<AcquisitionEvent> dispatched = harness_.merge(events);

      // 2 positions * 2 channels = 4 hardware-sequenced events, each a 10-step
      // z sequence, each recognized as z-sequenced.
      Assert.assertEquals("expected 4 merged (sequenced) events",
            4, dispatched.size());
      for (AcquisitionEvent e : dispatched) {
         Assert.assertNotNull("event should be a sequence", e.getSequence());
         Assert.assertEquals(10, e.getSequence().size());
         Assert.assertTrue("z should be hardware-sequenced", e.isZSequenced());
         // Within a sequenced event, z runs the full 10-step ramp...
         Assert.assertEquals(0.0, e.getSequence().get(0).getZPosition(), 1e-9);
         Assert.assertEquals(4.5, e.getSequence().get(9).getZPosition(), 1e-9);
         // ...while x/y stay constant across the sequence (single position).
         Assert.assertEquals(e.getSequence().get(0).getXPosition(),
               e.getSequence().get(9).getXPosition());
      }

      // The four merged events cover both XY positions, twice each (once/channel).
      int atPos1 = 0;
      int atPos2 = 0;
      for (AcquisitionEvent e : dispatched) {
         double x = e.getSequence().get(0).getXPosition();
         if (x == 100.0) {
            atPos1++;
         } else if (x == 300.0) {
            atPos2++;
         }
      }
      Assert.assertEquals(2, atPos1);
      Assert.assertEquals(2, atPos2);
   }

   // ---------------------------------------------------------------------------
   // Scenario 2b: same as above but z stage NOT sequenceable -> software z stack.
   // ---------------------------------------------------------------------------
   @Test
   public void multiPositionTwoChannelsSoftwareZStack() throws Exception {
      // Default: nothing sequenceable -> each slice is its own event and the
      // focus device is moved once per slice (the common, non-sequenced case).
      List<XYStagePosition> positions = Arrays.asList(
            xyPosition(100.0, 200.0, 0, 0),
            xyPosition(300.0, 400.0, 0, 1));
      List<ChannelSetting> channels =
            Arrays.asList(channel("DAPI", 10.0), channel("FITC", 20.0));

      Iterator<AcquisitionEvent> events = stream(Arrays.asList(
            AcqEventModules.positions(positions),
            AcqEventModules.channels(channels),
            AcqEventModules.zStack(0, 10, 0.5, 0.0)));

      List<AcquisitionEvent> dispatched = harness_.run(events);

      // 2 positions * 2 channels * 10 slices = 40 individual events.
      Assert.assertEquals(40, dispatched.size());
      for (AcquisitionEvent e : dispatched) {
         Assert.assertNull("events should not be sequenced", e.getSequence());
      }
      // 40 focus moves, no stage sequencing.
      Assert.assertEquals(40, core_.positionsSetFor(FOCUS).size());
      Assert.assertEquals(0, core_.countCommands("loadStageSequence " + FOCUS));
   }

   // ---------------------------------------------------------------------------
   // Scenario 3: timelapse x z stack x 2 channels, with autofocus before each
   // time point (a BEFORE_Z_DRIVE hook that runs the autofocus device).
   // ---------------------------------------------------------------------------
   @Test
   public void timelapseZStackTwoChannelsWithAutofocus() throws Exception {
      final List<Integer> autofocusedTimePoints = new ArrayList<>();
      // The autofocus hook runs after all other hardware is positioned and
      // before the Z drive moves (per startZDrive's contract). It fires once per
      // generated event here; we record the distinct time points it saw to prove
      // it runs at the start of every time point.
      acq_.addHook(new AcquisitionHook() {
         @Override
         public AcquisitionEvent run(AcquisitionEvent event) {
            core_.commands.add("autofocus@t" + event.getTIndex());
            autofocusedTimePoints.add(event.getTIndex());
            return event;
         }

         @Override
         public void close() {
         }
      }, AcquisitionAPI.BEFORE_Z_DRIVE_HOOK);

      List<ChannelSetting> channels =
            Arrays.asList(channel("DAPI", 10.0), channel("FITC", 20.0));

      Iterator<AcquisitionEvent> events = stream(Arrays.asList(
            AcqEventModules.timelapse(3, 0.0),
            AcqEventModules.channels(channels),
            AcqEventModules.zStack(0, 5, 1.0, 0.0)));

      harness_.run(events);

      // 3 time points * 2 channels * 5 slices = 30 events; the autofocus hook
      // ran on each, and covered all three time points.
      Assert.assertEquals(30, autofocusedTimePoints.size());
      Assert.assertEquals("autofocus should run across all three time points",
            new java.util.HashSet<>(Arrays.asList(0, 1, 2)),
            new java.util.HashSet<>(autofocusedTimePoints));

      // For each generated event the order must be: any device positioning
      // (setConfig/setXYPosition/non-focus stages) -> autofocus -> focus move.
      // Verify that every focus setPosition is immediately preceded (somewhere
      // earlier in the same event) by an autofocus marker, by checking that the
      // autofocus marker for a time point appears before that time point's focus
      // moves complete. Simpler invariant: an autofocus marker precedes every
      // focus setPosition in the command stream.
      assertAutofocusBeforeEachFocusMove(core_.commands);

      // 30 focus moves (one per slice; z stage not sequenceable here).
      Assert.assertEquals(30, core_.positionsSetFor(FOCUS).size());
   }

   private static void assertAutofocusBeforeEachFocusMove(List<String> commands) {
      boolean sawAutofocusSinceLastFocusMove = false;
      int focusMoves = 0;
      for (String c : commands) {
         if (c.startsWith("autofocus@")) {
            sawAutofocusSinceLastFocusMove = true;
         } else if (c.startsWith("setPosition " + FOCUS + " ")) {
            Assert.assertTrue(
                  "each focus move must be preceded by an autofocus run; "
                        + "offending command #" + focusMoves + ": " + c,
                  sawAutofocusSinceLastFocusMove);
            sawAutofocusSinceLastFocusMove = false;
            focusMoves++;
         }
      }
      Assert.assertEquals("expected 30 focus moves", 30, focusMoves);
   }

   private static XYStagePosition xyPosition(double x, double y, int row, int col) {
      return new XYStagePosition(new java.awt.geom.Point2D.Double(x, y), row, col);
   }
}
