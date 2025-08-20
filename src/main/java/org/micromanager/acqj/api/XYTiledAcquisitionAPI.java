package org.micromanager.acqj.api;

import org.micromanager.acqj.util.xytiling.CameraTilingStageTranslator;

/**
 * General interface for tiled XY multi-resolution acquisitions.
 *
 * @author henrypinkard
 */
public interface XYTiledAcquisitionAPI extends AcquisitionAPI {

   public CameraTilingStageTranslator getPixelStageTranslator();

}
