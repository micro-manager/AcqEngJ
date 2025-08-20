package org.micromanager.acqj.api;

import mmcorej.TaggedImage;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.main.Acquisition;

/**
 * Where the acquisition sends data to. Conventionally would be saving + image display.
 * When using AcqEngJ, either a DataSink or a custom TaggedImageProcessor that intercepts
 * and diverts images rather than passing them along should be implemented. Either one
 * represents a valid destination for images.
 *
 */
public interface AcqEngJDataSink {

   /**
    * Called when the Acquisition is initialized.
    *
    * @param acq
    * @param summaryMetadata 
    */
   public void initialize(Acquisition acq, JSONObject summaryMetadata);

   /**
    * Called when no more data will be collected. Block until all relevent resources are freed.
    */
   public void finish();

   /**
    * Is this sink and all associated resources complete (e.g. all data written to disk)
    */
   public boolean isFinished();

   /**
    * Add a new image to saving/display etc. If saving to disk,
    * do not return until the image written
    *
    * @param image
    * @return An optional object describing the image's location in the data set
    */
   public Object putImage(TaggedImage image);

   /**
    * Has putImage been called yet?
    *
    * @return 
    */
   public boolean anythingAcquired();


}
