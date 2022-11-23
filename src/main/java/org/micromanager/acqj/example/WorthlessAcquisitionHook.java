package org.micromanager.acqj.example;

import org.micromanager.acqj.api.AcquisitionHook;
import org.micromanager.acqj.main.AcquisitionEvent;

/**
 * This is a minimal implementation of an AcquistionHook meant for demonstration purposes only.
 * This AcquisitionHook gets run at a certain point in the acquisition cycle, but does nothing,
 * just passing along the acquisition event to subsequent stages. In practice, AcquisitionEvents
 * should do something
 */
public class WorthlessAcquisitionHook implements AcquisitionHook {
   @Override
   public AcquisitionEvent run(AcquisitionEvent event) {
      // Typically something would be done here
      System.out.println("Running a hook");
      return event;
   }

   @Override
   public void close() {
      //called at the end. Clean up resources as needed
   }

}
