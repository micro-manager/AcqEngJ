package org.micromanager.acqj.api.mda;

import org.micromanager.acqj.api.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.internal.acqengj.AcquisitionEventIterator;

/**
 * Class for the standardize Multi-D acquisition in micro-manager
 * Has fixed number of prespecified images along Channel, Slice,
 * Frame, Position axes
 *
 * NOTE:: this hasn't been tested/dpesnt work yet
 * 
 * @author henrypinkard
 */
public class MultiDAcquisition extends Acquisition {

   private MultiDAcqSettings settings_;
   
   public MultiDAcquisition( MultiDAcqSettings settings, DataSink sink) {
      super(sink);
      settings_ = settings;
   }

   protected Iterator<AcquisitionEvent> buildAcqEventGenerator() {
      List<Function<AcquisitionEvent, Iterator<AcquisitionEvent>>> functionList =
              new ArrayList<Function<AcquisitionEvent, Iterator<AcquisitionEvent>>>();
      
      MultiDAcqSettings settings = (MultiDAcqSettings) settings_;
      
      //This is an example of a T-P-C-Z acquisition
      functionList.add(AcqEventModules.timelapse(settings.numFrames_, settings.frameInterval_));
      functionList.add(AcqEventModules.positions(settings.xyPositions_));
      functionList.add(AcqEventModules.channels(settings.channels_));
      functionList.add(AcqEventModules.zStack(settings.minSliceIndex_, 
              settings.maxSliceIndex_, settings.zStep_, settings.zOrigin_));
      
      //TODO: add other orders and other features
      
      return new AcquisitionEventIterator(new AcquisitionEvent(this), functionList);
   }

   @Override
   public void addToSummaryMetadata(JSONObject summaryMetadata) {
      MultiDAcqSettings settings = (MultiDAcqSettings) settings_;

      AcqEngMetadata.setZStepUm(summaryMetadata, settings.zStep_);
      AcqEngMetadata.setZCTOrder(summaryMetadata, false);

      //TODO: what else is needed?
   }

   @Override
   public void addToImageMetadata(JSONObject tags) {
      //TODO: what else is needed?
   }



}
