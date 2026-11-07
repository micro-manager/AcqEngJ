package org.micromanager.acqj.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.micromanager.acqj.api.AcquisitionAPI;
import org.micromanager.acqj.api.AcquisitionHook;
import org.micromanager.acqj.example.BlackHoleDataSink;
import org.micromanager.acqj.main.Acquisition;
import org.micromanager.acqj.main.AcquisitionEvent;

/**
 * A failure of an event iterator, or of the events it produced, must abort the acquisition and
 * be stored for checkForExceptions() as the engine threw it, whichever of the engine's handlers
 * sees it; a stop is not a failure, so an event that fails after it stores nothing.
 *
 * <p>The tests use a hook that throws and a core whose camera refuses, so no hardware is touched
 * and no native library is needed.
 */
public class TestIteratorFailuresAbort {

   private static final String CORE_ERROR =
         "Error in device \"Cam\": Unknown error in the device (1)";

   private Acquisition acq_;

   @Before
   public void setUp() throws Exception {
      engineOn(new RecordingCMMCore("Z", "XY", "", "Cam"));
   }

   private void engineOn(RecordingCMMCore core) throws Exception {
      EngineTestHarness.resetEngineSingleton();
      new Engine(core);
      // initialize=false avoids makeSummaryMD(), which needs the native core.
      acq_ = new Acquisition(new BlackHoleDataSink(), false);
   }

   /** Throws on the first volume it sees, after a stop if asked, and drops every later one. */
   private static final class FailingHook implements AcquisitionHook {
      volatile int volumesSeen = 0;
      private final boolean stopFirst_;

      FailingHook(boolean stopFirst) {
         stopFirst_ = stopFirst;
      }

      @Override
      public AcquisitionEvent run(AcquisitionEvent event) {
         if (event.isAcquisitionFinishedEvent()) {
            return event;
         }
         volumesSeen++;
         if (volumesSeen == 1) {
            if (stopFirst_) {
               event.acquisition_.abort();
            }
            throw new RuntimeException("the first volume failed");
         }
         return null;
      }

      @Override
      public void close() {
      }
   }

   /** A core whose camera refuses to start its sequence, the way a camera adapter error does. */
   private static final class RefusingCore extends RecordingCMMCore {
      RefusingCore() {
         super("Z", "XY", "", "Cam");
      }

      @Override
      public void startSequenceAcquisition(String camera, int numImages, double intervalMs,
                                           boolean stopOnOverflow) throws Exception {
         throw new Exception(CORE_ERROR);
      }
   }

   /**
    * Events of one time point that the engine merges into one hardware sequence: z is an axis
    * label here, not a stage position, so no stage move keeps them apart.
    */
   private Iterator<AcquisitionEvent> volume(int timeIndex, String camera) {
      List<AcquisitionEvent> events = new ArrayList<>();
      for (int z = 0; z < 3; z++) {
         AcquisitionEvent e = new AcquisitionEvent((AcquisitionAPI) acq_);
         e.setTimeIndex(timeIndex);
         e.setAxisPosition("z", z);
         if (camera != null) {
            e.setCameraDeviceName(camera);
         }
         events.add(e);
      }
      return events.iterator();
   }

   /**
    * Two time points with different minimum start times in one iterator, so the engine executes
    * the first one's events in mid stream, when the second one's first event arrives.
    */
   private Iterator<AcquisitionEvent> twoTimePoints() {
      List<AcquisitionEvent> events = new ArrayList<>();
      for (int t = 0; t < 2; t++) {
         for (int z = 0; z < 3; z++) {
            AcquisitionEvent e = new AcquisitionEvent((AcquisitionAPI) acq_);
            e.setTimeIndex(t);
            e.setAxisPosition("z", z);
            e.setMinimumStartTime(t * 10L);
            events.add(e);
         }
      }
      return events.iterator();
   }

   /** Two volumes the engine cannot merge, because the exposure is not sequenceable. */
   private Iterator<AcquisitionEvent> twoExposures() {
      List<AcquisitionEvent> events = new ArrayList<>();
      for (double exposure : new double[] {10, 20}) {
         for (int z = 0; z < 3; z++) {
            AcquisitionEvent e = new AcquisitionEvent((AcquisitionAPI) acq_);
            e.setAxisPosition("z", z);
            e.setExposure(exposure);
            events.add(e);
         }
      }
      return events.iterator();
   }

   private Exception stored() {
      try {
         acq_.checkForExceptions();
         return null;
      } catch (Exception e) {
         return e;
      }
   }

   private void finishAndWait() throws Exception {
      // what a client does once every iterator is submitted
      acq_.finish();
      acq_.waitForCompletion();
   }

   /**
    * The failure seen on hardware: a camera refuses to start the sequence of the events queued
    * for the end of the iterator. The engine throws its own HardwareControlException with the
    * core's text, and that is what checkForExceptions() must throw.
    */
   @Test(timeout = 10000)
   public void aCameraThatRefusesToStartAbortsAndStoresTheEnginesOwnError() throws Exception {
      engineOn(new RefusingCore());

      acq_.submitEventIterator(volume(0, "Cam"));
      acq_.submitEventIterator(volume(1, "Cam"));
      finishAndWait();

      Assert.assertTrue("the refusal must abort the acquisition", acq_.isAbortRequested());
      Exception stored = stored();
      Assert.assertNotNull("the refusal must be stored for checkForExceptions()", stored);
      Assert.assertEquals("the engine's own error must be stored, not the Future's wrapper"
            + " around it: " + stored, HardwareControlException.class, stored.getClass());
      Assert.assertEquals(CORE_ERROR, stored.getMessage());
   }

   /** The handler that already aborted: an event executed when the next one cannot join it. */
   @Test(timeout = 10000)
   public void aFailureOfAnEventExecutedMidStreamStoresTheEnginesOwnException()
         throws Exception {
      FailingHook hook = new FailingHook(false);
      acq_.addHook(hook, Acquisition.BEFORE_HARDWARE_HOOK);

      acq_.submitEventIterator(twoTimePoints());
      finishAndWait();

      Assert.assertTrue("the failure must abort the acquisition", acq_.isAbortRequested());
      Assert.assertEquals("the first time point must reach the hook once", 1, hook.volumesSeen);
      Exception stored = stored();
      Assert.assertNotNull("the failure must be stored for checkForExceptions()", stored);
      Assert.assertEquals("checkForExceptions() must throw what the engine threw, not the"
            + " Future's wrapper: " + stored, RuntimeException.class, stored.getClass());
      Assert.assertEquals("the first volume failed", stored.getMessage());
   }

   /**
    * Before the first event the engine has not seen which acquisition the iterator belongs to,
    * so the acquisition passes itself to the engine when it submits.
    */
   @Test(timeout = 10000)
   public void anIteratorThatThrowsBeforeItsFirstEventAbortsTheAcquisition() throws Exception {
      Iterator<AcquisitionEvent> failingAtOnce = new Iterator<AcquisitionEvent>() {
         @Override
         public boolean hasNext() {
            return true;
         }

         @Override
         public AcquisitionEvent next() {
            throw new RuntimeException("the event generator failed before its first event");
         }
      };

      acq_.submitEventIterator(failingAtOnce);
      finishAndWait();

      Assert.assertTrue("the failure must abort the acquisition", acq_.isAbortRequested());
      Exception stored = stored();
      Assert.assertNotNull("the failure must be stored for checkForExceptions()", stored);
      Assert.assertEquals("the event generator failed before its first event",
            stored.getMessage());
   }

   /**
    * Explore submits this when every tile of a click is already acquired, and multi MDA
    * when skip frames leaves a time point with no images. It must not end the acquisition.
    */
   @Test(timeout = 10000)
   public void anIteratorWithNoEventsDoesNotAbort() throws Exception {
      // get() throws if the iterator throws at its end instead of doing nothing
      acq_.submitEventIterator(
            Collections.<AcquisitionEvent>singletonList(null).iterator()).get();
      finishAndWait();

      Assert.assertFalse(acq_.isAbortRequested());
      Assert.assertNull(stored());
   }

   /**
    * A failure mid stream leaves the next event queued until the acquisition's finish, which
    * runs after every iterator already submitted. An iterator with no events among them must not
    * execute it.
    */
   @Test(timeout = 10000)
   public void anIteratorWithNoEventsDoesNotExecuteWhatAFailedOneLeftQueued() throws Exception {
      FailingHook hook = new FailingHook(false);
      acq_.addHook(hook, Acquisition.BEFORE_HARDWARE_HOOK);

      // hold the first iterator at its first event until the second is queued behind it
      acq_.setPaused(true);
      acq_.submitEventIterator(twoExposures());
      acq_.submitEventIterator(Collections.<AcquisitionEvent>emptyIterator());
      acq_.setPaused(false);
      finishAndWait();

      Assert.assertTrue("the failure must abort the acquisition", acq_.isAbortRequested());
      Assert.assertEquals("the event the failed volume left queued must not execute", 1,
            hook.volumesSeen);
   }

   /**
    * Cancelling an iterator's Future while the acquisition is paused interrupts the engine's
    * pause wait. A cancel is not a failure: the acquisition goes on and stores nothing.
    */
   @Test(timeout = 10000)
   public void cancellingAnIteratorWhilePausedIsNotAFailure() throws Exception {
      final CountDownLatch reachedPause = new CountDownLatch(1);
      acq_.addHook(new AcquisitionHook() {
         @Override
         public AcquisitionEvent run(AcquisitionEvent event) {
            // event generation hooks run just before the pause wait
            reachedPause.countDown();
            return event;
         }

         @Override
         public void close() {
         }
      }, Acquisition.EVENT_GENERATION_HOOK);

      acq_.setPaused(true);
      Future<?> iterator = acq_.submitEventIterator(volume(0, null));
      Assert.assertTrue(reachedPause.await(5, TimeUnit.SECONDS));
      iterator.cancel(true);
      // iterators run one at a time, so this returns only after the cancelled one has exited
      acq_.submitEventIterator(Collections.<AcquisitionEvent>emptyIterator()).get();
      acq_.setPaused(false);
      finishAndWait();

      Assert.assertFalse("a cancel is not a failure: " + stored(), acq_.isAbortRequested());
      Assert.assertNull(stored());
   }

   /**
    * After a stop, a failure of the events queued for the end of the iterator stores nothing:
    * a stop is not a failure.
    */
   @Test(timeout = 10000)
   public void aFailureOfTheQueuedEventsAfterAStopStoresNothing() throws Exception {
      FailingHook hook = new FailingHook(true);
      acq_.addHook(hook, Acquisition.BEFORE_HARDWARE_HOOK);

      acq_.submitEventIterator(volume(0, null));
      acq_.submitEventIterator(volume(1, null));
      finishAndWait();

      Assert.assertTrue(acq_.isAbortRequested());
      Assert.assertNull("a stop is not a failure: " + stored(), stored());
      Assert.assertEquals(1, hook.volumesSeen);
   }

   /** After a stop, a failure of an event executed in mid stream stores nothing too. */
   @Test(timeout = 10000)
   public void aFailureOfAnEventExecutedMidStreamAfterAStopStoresNothing() throws Exception {
      FailingHook hook = new FailingHook(true);
      acq_.addHook(hook, Acquisition.BEFORE_HARDWARE_HOOK);

      acq_.submitEventIterator(twoTimePoints());
      finishAndWait();

      Assert.assertTrue(acq_.isAbortRequested());
      Assert.assertNull("a stop is not a failure: " + stored(), stored());
      Assert.assertEquals(1, hook.volumesSeen);
   }
}
