package org.micromanager.acqj.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import org.micromanager.acqj.api.AcquisitionHook;
import org.micromanager.acqj.main.Acquisition;
import org.micromanager.acqj.main.AcquisitionEvent;

/**
 * Test harness that drives the real {@link Engine} hardware-control code against
 * a {@link RecordingCMMCore}, without the native MMCoreJ library and without the
 * background-thread / image-acquisition machinery.
 *
 * <p>It reproduces the parts of the engine's private pipeline that matter for
 * "what hardware commands get issued":
 * <ol>
 *   <li>the queue/merge loop from {@code Engine.processAcquisitionEvent}
 *       ({@code isSequencable} + {@code mergeSequenceEvent}), and</li>
 *   <li>the per-event order from {@code Engine.executeAcquisitionEvent}:
 *       {@code prepareHardware} -> before-Z-drive hooks -> {@code startZDrive},
 *       updating {@code lastEvent_} between events exactly as the engine does.</li>
 * </ol>
 *
 * <p>All private members are reached by reflection. Everything else (event
 * generation, the modules, the merge logic) is the production code under test.
 */
public class EngineTestHarness {

   private final Engine engine_;
   private final RecordingCMMCore core_;
   private final Acquisition acquisition_;

   private final Method prepareHardware_;
   private final Method startZDrive_;
   private final Method isSequencable_;
   private final Method mergeSequenceEvent_;
   private final Field lastEvent_;

   public EngineTestHarness(RecordingCMMCore core, Acquisition acquisition) throws Exception {
      core_ = core;
      acquisition_ = acquisition;
      resetEngineSingleton();
      engine_ = new Engine(core);
      if (Engine.getCore() != core) {
         throw new IllegalStateException("Engine is not using the recording core");
      }

      prepareHardware_ = Engine.class.getDeclaredMethod(
            "prepareHardware", AcquisitionEvent.class, HardwareSequences.class);
      prepareHardware_.setAccessible(true);
      startZDrive_ = Engine.class.getDeclaredMethod(
            "startZDrive", AcquisitionEvent.class, HardwareSequences.class);
      startZDrive_.setAccessible(true);
      isSequencable_ = Engine.class.getDeclaredMethod(
            "isSequencable", List.class, AcquisitionEvent.class, int.class);
      isSequencable_.setAccessible(true);
      mergeSequenceEvent_ = Engine.class.getDeclaredMethod(
            "mergeSequenceEvent", List.class);
      mergeSequenceEvent_.setAccessible(true);
      lastEvent_ = Engine.class.getDeclaredField("lastEvent_");
      lastEvent_.setAccessible(true);
   }

   public RecordingCMMCore core() {
      return core_;
   }

   /**
    * Consume an event stream the way the engine does: greedily merge sequencable
    * events, then execute each merged event. Returns the list of merged events
    * that were dispatched to the hardware (useful for asserting how many hardware
    * "shots" occurred and which were hardware-sequenced).
    */
   public List<AcquisitionEvent> run(Iterator<AcquisitionEvent> events) throws Exception {
      List<AcquisitionEvent> dispatched = new ArrayList<>();
      LinkedList<AcquisitionEvent> queue = new LinkedList<>();
      while (events.hasNext()) {
         AcquisitionEvent event = events.next();
         if (event == null) {
            continue;
         }
         if (queue.isEmpty()) {
            queue.add(event);
         } else if (isSequencable(queue, event, queue.size() + 1)) {
            queue.add(event);
         } else {
            dispatched.add(executeMerged(queue));
            queue.clear();
            queue.add(event);
         }
      }
      if (!queue.isEmpty()) {
         dispatched.add(executeMerged(queue));
      }
      return dispatched;
   }

   private AcquisitionEvent executeMerged(List<AcquisitionEvent> queue) throws Exception {
      AcquisitionEvent merged = mergeSequenceEvent(queue);
      executeEvent(merged);
      return merged;
   }

   /**
    * Run only the queue/merge loop (the engine's sequencing <em>decision</em>),
    * returning the merged events that would be dispatched, WITHOUT executing
    * them on the hardware.
    *
    * <p>Use this for hardware-sequenced scenarios: the engine's
    * {@code startZDrive}/{@code prepareHardware} build native {@code DoubleVector}
    * sequence objects (a JNI call) that cannot run without the native MMCoreJ
    * library. This method validates how events are grouped and which axes are
    * sequenced, which is native-free.
    */
   public List<AcquisitionEvent> merge(Iterator<AcquisitionEvent> events) throws Exception {
      List<AcquisitionEvent> dispatched = new ArrayList<>();
      LinkedList<AcquisitionEvent> queue = new LinkedList<>();
      while (events.hasNext()) {
         AcquisitionEvent event = events.next();
         if (event == null) {
            continue;
         }
         if (queue.isEmpty()) {
            queue.add(event);
         } else if (isSequencable(queue, event, queue.size() + 1)) {
            queue.add(event);
         } else {
            dispatched.add(mergeSequenceEvent(queue));
            queue.clear();
            queue.add(event);
         }
      }
      if (!queue.isEmpty()) {
         dispatched.add(mergeSequenceEvent(queue));
      }
      return dispatched;
   }

   /**
    * Mirror the hardware-moving portion of {@code executeAcquisitionEvent}:
    * prepareHardware, then before-Z-drive hooks, then startZDrive, then update
    * lastEvent_.
    */
   public void executeEvent(AcquisitionEvent event) throws Exception {
      HardwareSequences seqs = new HardwareSequences();
      invoke(prepareHardware_, event, seqs);
      if (acquisition_ != null) {
         for (AcquisitionHook h : acquisition_.getBeforeZDriveHooks()) {
            event = h.run(event);
         }
      }
      invoke(startZDrive_, event, seqs);
      setLastEvent(event.getSequence() == null
            ? event
            : event.getSequence().get(event.getSequence().size() - 1));
   }

   public boolean isSequencable(List<AcquisitionEvent> previous,
                                AcquisitionEvent next, int newSeqLength) throws Exception {
      return (Boolean) isSequencable_.invoke(null, previous, next, newSeqLength);
   }

   public AcquisitionEvent mergeSequenceEvent(List<AcquisitionEvent> list) throws Exception {
      return (AcquisitionEvent) mergeSequenceEvent_.invoke(engine_, list);
   }

   public void setLastEvent(AcquisitionEvent event) throws Exception {
      lastEvent_.set(engine_, event);
   }

   private void invoke(Method m, Object... args) throws Exception {
      try {
         m.invoke(engine_, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
         Throwable cause = e.getCause();
         if (cause instanceof Exception) {
            throw (Exception) cause;
         }
         throw e;
      }
   }

   public static void resetEngineSingleton() throws Exception {
      Field singleton = Engine.class.getDeclaredField("singleton_");
      singleton.setAccessible(true);
      singleton.set(null, null);
      Field core = Engine.class.getDeclaredField("core_");
      core.setAccessible(true);
      core.set(null, null);
   }
}
