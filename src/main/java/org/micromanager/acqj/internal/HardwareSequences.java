package org.micromanager.acqj.internal;

import java.util.ArrayList;
import mmcorej.CMMCore;

/**
 * Class for holding data about what hardware sequences are currently being run
 */
public class HardwareSequences {

   public ArrayList<String> deviceNames = new ArrayList<>();
   public ArrayList<String> propertyNames = new ArrayList<>();
   public ArrayList<String> propertyDeviceNames = new ArrayList<>();

}
