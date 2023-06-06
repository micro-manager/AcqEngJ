package org.micromanager.acqj.main;

import java.util.HashMap;
import mmcorej.org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.micromanager.acqj.example.BlackHoleDataSink;

public class TestAcquisitionEvent {
   @Test
   public void testSetupIsSane() {
      Assert.assertTrue("must be true", true);
   }

   @Test
   public void testEventSerialization() {
      BlackHoleDataSink bhds = new BlackHoleDataSink();
      Acquisition acq = new Acquisition(bhds);
      AcquisitionEvent event = new AcquisitionEvent(acq);
      final int z = 2;
      event.setZ(z, z * 0.5);
      final int t = 3;
      event.setTimeIndex(t);
      final long minimumStartTime = 892567265;
      event.setMinimumStartTime(minimumStartTime);
      HashMap<String, String> tags = new HashMap<>();
      tags.put("test", "test");
      tags.put("test2", "test2");

      JSONObject eventSerial = event.toJSON();
      AcquisitionEvent eventCopy = AcquisitionEvent.fromJSON(eventSerial, acq);

      Assert.assertTrue(eventCopy.getZIndex() == z);
      Assert.assertEquals(eventCopy.getZPosition(), z * 0.5, 0.0000001);
      Assert.assertTrue(eventCopy.getTIndex() == t);
      Assert.assertEquals(eventCopy.getMinimumStartTimeAbsolute(), minimumStartTime, 2000);
      Assert.assertEquals(eventCopy.getTags().get("test"), event.getTags().get("test"));
      Assert.assertEquals(eventCopy.getTags().get("test2"), event.getTags().get("test2"));
   }
}

