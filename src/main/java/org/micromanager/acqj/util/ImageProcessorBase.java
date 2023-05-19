package org.micromanager.acqj.util;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import mmcorej.TaggedImage;
import org.micromanager.acqj.api.AcquisitionAPI;
import org.micromanager.acqj.api.TaggedImageProcessor;


/**
 * An abstract class that handles a the threading to implement an image processor
 * that runs asynchronously--i.e. on its own thread that is different from the one
 * that is pulling images from the Core, and different from any subsequent
 * processors/writing images to disk (if applicable)
 *
 * @author henrypinkard
 */
public abstract class ImageProcessorBase implements TaggedImageProcessor {

   private ExecutorService imageProcessorExecutor_;

   AcquisitionAPI acq_;
   protected volatile LinkedBlockingDeque<TaggedImage> source_, sink_;

   public ImageProcessorBase () {
      imageProcessorExecutor_ = Executors.newSingleThreadExecutor(
            (Runnable r) -> new Thread(r, "Image processor thread"));
   }

   protected abstract TaggedImage processImage(TaggedImage img);

   protected void addToOutputQueue(TaggedImage img) {
      try {
         sink_.putLast(img);
      } catch (InterruptedException e) {
         throw new RuntimeException("Unexpected problem in image processor");
      }
   }

   @Override
   public void setAcqAndDequeues(AcquisitionAPI acq,
         LinkedBlockingDeque<TaggedImage> source, LinkedBlockingDeque<TaggedImage> sink) {
      // This function is called automatically by the acquisition engine on startup.
      // Once it is called and the Dequeus are set, processing can begin
      acq_ = acq;
      source_ = source;
      sink_ = sink;
      imageProcessorExecutor_.submit(new Runnable() {
         @Override
         public void run() {
            while (true) {
               TaggedImage img;
               try {

                  try {
                     img = source_.takeFirst();
                     if (img.tags == null && img.pix == null) {
                        // time to shut down
                        // tell the subclass
                        processImage(img);
                        // propagate shutdown signal forward so that anything downstream also shuts down
                        sink_.putLast(img);
                        imageProcessorExecutor_.shutdown();
                        break;
                     }
                  } catch (InterruptedException e) {
                     // This should never happen
                     throw new RuntimeException("Unexpected problem in image processor");
                  }

                  TaggedImage result = processImage(img);
                  if (result != null) {
                     sink_.putLast(result);
                  }
               } catch (Exception e) {
                  // Uncaught exception in image processor
                  acq_.abort(e);
               }
            }

         }
      });
   }



}
