package org.micromanager.acqj.example;

import java.util.Iterator;
import java.util.concurrent.Future;
import org.micromanager.acqj.api.AcqEngJDataSink;
import org.micromanager.acqj.api.AcquisitionAPI;
import org.micromanager.acqj.main.Acquisition;
import org.micromanager.acqj.main.AcquisitionEvent;
import org.micromanager.acqj.util.AcqEventModules;

/**
 * This class demonstrates how to setup an acquisition using AcqEngJ
 */
public class FullExample {

   public FullExample() {

      // Create a DataSink where images will be sent
      AcqEngJDataSink dataSink = new BlackHoleDataSink();

      // Create the acquisition
      AcquisitionAPI acquisition = new Acquisition(dataSink);

      // Create an acquisition hook, which will be run on the same thread that controls
      // hardware
      acquisition.addHook(new WorthlessAcquisitionHook(), AcquisitionAPI.AFTER_HARDWARE_HOOK);

      // Create an image processor, which will run on its own thread
      acquisition.addImageProcessor(new WorthlessImageProcessor());

      // Ready the acquisiton to receive events
      acquisition.start();

      // Now that everything is setup, AcquistionEvents can be added in order to
      // start acquiring data.
      // Each AcquisitionEvent will order the collection of one image per camera
      // Acquisitions accept lazy sequences of AcquisitionEvents, in for form of
      // an Iterator<AcquisitionEvent>.
      // below is an example of using a convenience function AcqEventModules to order the
      // collection a 5 image timelapse with 0 interval between images.
      Iterator<AcquisitionEvent> lazySequence = AcqEventModules.timelapse(
            5, 0).apply(new AcquisitionEvent(acquisition));

      // Make sure that all desired hooks and image processors have been added by this point,
      // or else they may miss the Events/images
      Future result = acquisition.submitEventIterator(lazySequence);

      //Submitting an event iterator returns a Future. Since the actual acquisition occurs on a
      // different thread, submitEventIterotor will return immediately. The future can be used
      // to check on the progress of the events:

      // Calling get will block until the events are acquired, or throw an exceoption if something
      // went wrong
      try {
         result.get();
      } catch (Exception e) {
         // Something went wrong when acquiring the data
      }

      // Cancel the execution of the previously submitted events
      result.cancel(true);

      // submitEventIterator can be called again, if desired


      // once no more events will be submitted, signal the acquisition to shutdown.
      // This will automatically handle the orderly shutdown of all DataSinks/Hooks/Image processors
      acquisition.finish();

      // Block until everythin cleaned up
      acquisition.waitForCompletion();
   }



}
