package org.micromanager.acqj.example;

import mmcorej.TaggedImage;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.api.AcqEngJDataSink;
import org.micromanager.acqj.main.Acquisition;

/**
 * This is a minimal implementation of a DataSink meant for demonstration purposes only. This
 * DataSink acts as a black hole, immediately discarding every image it recieves forever.
 * In practice, DataSinks should instead do something useful (e.g. save and display)
 * with the images they receive
 */
public class BlackHoleDataSink implements AcqEngJDataSink {

   boolean finished_ = false;
   boolean somethingAcquired_ = false;

   @Override
   public void initialize(Acquisition acq, JSONObject summaryMetadata) {

   }

   @Override
   public void finish() {
      finished_ = true;
   }

   @Override
   public boolean isFinished() {
      return finished_;
   }

   @Override
   public void putImage(TaggedImage image) {
      somethingAcquired_ = true;
      System.out.println("throwing away an image forever");
   }

   @Override
   public boolean anythingAcquired() {
      return somethingAcquired_;
   }
}
