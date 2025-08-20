package org.micromanager.acqj.util;

import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.micromanager.acqj.internal.Engine;
import org.micromanager.acqj.main.AcqEngMetadata;
import org.micromanager.acqj.main.AcquisitionEvent;
import org.micromanager.acqj.util.xytiling.XYStagePosition;

/**
 * A utility class with multiple "modules" functions for creating common
 * acquisition functions that can be combined to encode complex behaviors
 *
 * @author henrypinkard
 */
public class AcqEventModules {

   public static Function<AcquisitionEvent, Iterator<AcquisitionEvent>>
         zStack(int startSliceIndex, int stopSliceIndex, double zStep, double zOrigin) {
      return (AcquisitionEvent event) -> {
         return new Iterator<AcquisitionEvent>() {

            private int zIndex_ = startSliceIndex;

            @Override
            public boolean hasNext() {
               return zIndex_ < stopSliceIndex;
            }

            @Override
            public AcquisitionEvent next() {
               double zPos = zIndex_ * zStep + zOrigin;
               AcquisitionEvent sliceEvent = event.copy();
               //Do plus equals here in case z positions have been modified by another function
               // (e.g. channel specific focal offsets)
               sliceEvent.setZ(zIndex_,
                       (sliceEvent.getZPosition() == null ? 0.0
                             : sliceEvent.getZPosition()) + zPos);
               zIndex_++;
               return sliceEvent;
            }
         };
      };
   }

   /**
    * Generic version of a z stack that corresponds to an arbitrary sequence of linearly
    * spaced points along a stage.
    *
    * @return
    */
   public static Function<AcquisitionEvent, Iterator<AcquisitionEvent>> moveStage(
           String deviceName,
           int startIndex, int stopIndex, double step, double origin) {
      return (AcquisitionEvent event) -> {
         return new Iterator<AcquisitionEvent>() {

            private int index_ = startIndex;

            @Override
            public boolean hasNext() {
               return index_ < stopIndex;
            }

            @Override
            public AcquisitionEvent next() {
               double pos = index_ * step + origin;
               AcquisitionEvent sliceEvent = event.copy();
               // Do plus equals here in case z positions have been modified by another
               // function (e.g. channel specific focal offsets)
               sliceEvent.setStageCoordinate(deviceName,
                       (sliceEvent.getStageSingleAxisStagePosition(deviceName) == null ? 0.0
                               : sliceEvent.getStageSingleAxisStagePosition(deviceName)) + pos);
               sliceEvent.setAxisPosition(sliceEvent.getDeviceAxisName(deviceName), index_);
               index_++;
               return sliceEvent;
            }
         };
      };
   }


   public static Function<AcquisitionEvent, Iterator<AcquisitionEvent>>
         timelapse(int numTimePoints, double intervalMs) {
      return (AcquisitionEvent event) -> {
         return new Iterator<AcquisitionEvent>() {

            int frameIndex_ = 0;

            @Override
            public boolean hasNext() {
               if (frameIndex_ == 0) {
                  return true;
               }
               if (frameIndex_ < numTimePoints) {
                  return true;
               }
               return false;
            }

            @Override
            public AcquisitionEvent next() {
               AcquisitionEvent timePointEvent = event.copy();

               timePointEvent.setMinimumStartTime((long) (intervalMs * frameIndex_));
               
               timePointEvent.setTimeIndex(frameIndex_);
               frameIndex_++;

               return timePointEvent;
            }
         };
      };
   }

   /**
    * Make an iterator for events for each active channel.
    *
    * @param channelList 
    * @return
    */
   public static Function<AcquisitionEvent, Iterator<AcquisitionEvent>>
         channels(List<ChannelSetting> channelList) {
      return (AcquisitionEvent event) -> {
         return new Iterator<AcquisitionEvent>() {
            int index = 0;

            @Override
            public boolean hasNext() {
               return index < channelList.size();
            }

            @Override
            public AcquisitionEvent next() {
               AcquisitionEvent channelEvent = event.copy();
               channelEvent.setConfigGroup(channelList.get(index).group_);
               channelEvent.setConfigPreset(channelList.get(index).config_);
               channelEvent.setChannelName(channelList.get(index).config_);
               boolean hasZOffsets = channelList.stream().map(t -> t.offset_)
                           .filter(t -> t != 0).collect(Collectors.toList()).size() > 0;
               Double zPos = null;
               if (channelEvent.getZPosition() != null) {
                  zPos = channelEvent.getZPosition();
               }
               if (channelEvent.getStageSingleAxisStagePosition(Engine.getCore().getFocusDevice())
                     != null) {
                  if (zPos != null) {
                     throw new RuntimeException(
                           "Can't have both a z position and a named axis focus position");
                  } else {
                     zPos = channelEvent.getStageSingleAxisStagePosition(
                           Engine.getCore().getFocusDevice());
                  }
               }

               if (zPos == null) {
                  if (hasZOffsets) {
                     try {
                        zPos = Engine.getCore().getPosition() + channelList.get(index).offset_;
                     } catch (Exception e) {
                        throw new RuntimeException(e);
                     }
                  } else {
                     zPos = null;
                  }
               } else {
                  zPos += hasZOffsets ? channelList.get(index).offset_ : 0;
               }
               if (zPos != null) {
                  // Its either stored as a named stage or as "z", keep it con
                  if (channelEvent.getStageSingleAxisStagePosition(
                        Engine.getCore().getFocusDevice()) != null) {
                     channelEvent.setStageCoordinate(Engine.getCore().getFocusDevice(), zPos);
                  } else {
                     channelEvent.setZ(channelEvent.getZIndex(), zPos);
                  }
               }

               channelEvent.setExposure(channelList.get(index).exposure_);
               index++;
               return channelEvent;
            }
         };
      };
   }

   /**
    * Iterate over an arbitrary list of positions. Adds in postition indices to
    * the axes that assumer the order in the list provided correspond to the
    * desired indices
    *
    * @param positions
    * @return
    */
   public static Function<AcquisitionEvent, Iterator<AcquisitionEvent>>
         positions(List<XYStagePosition> positions) {
      return (AcquisitionEvent event) -> {
         Stream.Builder<AcquisitionEvent> builder = Stream.builder();
         if (positions == null) {
            builder.accept(event);
         } else {
            for (int index = 0; index < positions.size(); index++) {
               AcquisitionEvent posEvent = event.copy();
               posEvent.setX(positions.get(index).getCenter().x);
               posEvent.setY(positions.get(index).getCenter().y);
               posEvent.setAxisPosition(AcqEngMetadata.AXES_GRID_ROW,
                     positions.get(index).getGridRow());
               posEvent.setAxisPosition(AcqEngMetadata.AXES_GRID_COL,
                     positions.get(index).getGridCol());
               builder.accept(posEvent);
            }
         }
         return builder.build().iterator();
      };
   }


}
