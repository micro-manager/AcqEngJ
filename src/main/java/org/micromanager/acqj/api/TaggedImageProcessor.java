package org.micromanager.acqj.api;

import java.util.concurrent.LinkedBlockingDeque;
import mmcorej.TaggedImage;
import org.micromanager.acqj.api.AcquisitionAPI;


/**
 * Image processors are responsible for shutting down and releasing resources automatically,
 * once a TaggedImage(null, null) appears in the source queue. is a processor gets this signal
 * on its source queue, it is responsible for propagating the message to its sink queue so downstream
 * resources can also be released
 */
public interface TaggedImageProcessor {

   /**
    * Class for modifying/adding/deleting images after they're acquired before 
    * saving. Expects the implementing class to be pulling data off of the front
    * of the source Dequeue, and then optionally adding it to the end of the sink
    * Dequeue. This method will get called immediately
    * after adding the TaggedImageProcessor.
    *
    * @param acq The acquisition which this processor will operate on
    * @param source The source of images that sould be processed
    * @param sink The destination of any processed results
    */
   public void setAcqAndDequeues(AcquisitionAPI acq,
         LinkedBlockingDeque<TaggedImage> source,
                                 LinkedBlockingDeque<TaggedImage> sink);
   
}