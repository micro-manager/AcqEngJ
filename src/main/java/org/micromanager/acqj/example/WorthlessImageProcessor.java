package org.micromanager.acqj.example;

import mmcorej.TaggedImage;
import org.micromanager.acqj.util.ImageProcessorBase;

public class WorthlessImageProcessor extends ImageProcessorBase {


   /**
    * This is a minimal implementation of an ImageProcessor meant for demonstration purposes only.
    * This ImageProcessor simply takes an example TaggedImage and passes it along, without
    * actually doing any processing. In practice, ImageProcessors should do something useful:
    * e.g., analyze the image and take some action, modify the image, or divert the image
    * to a customized savaing pipeline.
    */
   @Override
   protected TaggedImage processImage(TaggedImage img) {
      // TODO: do something with the image
      System.out.println("Processing an image");
      return img;
   }

}
