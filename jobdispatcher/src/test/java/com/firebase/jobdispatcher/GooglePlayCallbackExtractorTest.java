// Copyright 2016 Google, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.firebase.jobdispatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import com.firebase.jobdispatcher.GooglePlayCallbackExtractorTest.ExtendedShadowParcel;
import com.firebase.jobdispatcher.TestUtil.InspectableBinder;
import com.firebase.jobdispatcher.TestUtil.TransactionArguments;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowParcel;

/** Tests for the {@link GooglePlayCallbackExtractor} class. */
@RunWith(RobolectricTestRunner.class)
@Config(
  manifest = Config.NONE,
  sdk = 23,
  shadows = {ExtendedShadowParcel.class}
)
public final class GooglePlayCallbackExtractorTest {
  /**
   * ShadowParcel doesn't correctly handle {@link Parcel#writeStrongBinder(IBinder)} or {@link
   * Parcel#readStrongBinder()}, so we shim a simple implementation that uses an in-memory map to
   * read and write Binder objects.
   */
  @Implements(Parcel.class)
  public static class ExtendedShadowParcel extends ShadowParcel {
    @RealObject private Parcel realObject;

    // Map each IBinder to an integer, and use the super's int-writing capability to fake Binder
    // read/writes.
    private final AtomicInteger nextBinderId = new AtomicInteger(1);
    private final Map<Integer, IBinder> binderMap =
        Collections.synchronizedMap(new HashMap<Integer, IBinder>());

    @Implementation
    public void writeStrongBinder(IBinder binder) {
      int id = nextBinderId.getAndIncrement();
      binderMap.put(id, binder);
      realObject.writeInt(id);
    }

    @Implementation
    public IBinder readStrongBinder() {
      return binderMap.get(realObject.readInt());
    }
  }

  @Mock private IBinder binder;

  private GooglePlayCallbackExtractor extractor;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    extractor = new GooglePlayCallbackExtractor();
  }

  @Test
  public void testExtractCallback_nullBundle() {
    assertNull(extractor.extractCallback(null));
  }

  @Test
  public void testExtractCallback_nullParcelable() {
    Bundle emptyBundle = new Bundle();
    assertNull(extractCallback(emptyBundle));
  }

  @Test
  public void testExtractCallback_badParcelable() {
    Bundle misconfiguredBundle = new Bundle();
    misconfiguredBundle.putParcelable("callback", new BadParcelable(1));

    assertNull(extractCallback(misconfiguredBundle));
  }

  @Test
  public void testExtractCallback_goodParcelable() {
    InspectableBinder binder = new InspectableBinder();
    Bundle validBundle = new Bundle();
    validBundle.putParcelable("callback", binder.toPendingCallback());

    Pair<JobCallback, Bundle> extraction = extractCallback(validBundle);
    assertNotNull(extraction);
    assertEquals(
        "should have stripped the 'callback' entry from the extracted bundle",
        0,
        extraction.second.keySet().size());
    extraction.first.jobFinished(JobService.RESULT_SUCCESS);

    // Check our homemade Binder is doing the right things:
    TransactionArguments args = binder.getArguments().get(0);
    // Should have set the transaction code:
    assertEquals("transaction code", IBinder.FIRST_CALL_TRANSACTION + 1, args.code);

    // strong mode bit
    args.data.readInt();
    // interface token
    assertEquals("com.google.android.gms.gcm.INetworkTaskCallback", args.data.readString());
    // result
    assertEquals("result", JobService.RESULT_SUCCESS, args.data.readInt());
  }

  @Test
  public void testExtractCallback_extraMapValues() {
    Bundle validBundle = new Bundle();
    validBundle.putString("foo", "bar");
    validBundle.putInt("bar", 3);
    validBundle.putParcelable("parcelable", new Bundle());
    validBundle.putParcelable("callback", new InspectableBinder().toPendingCallback());

    Pair<JobCallback, Bundle> extraction = extractCallback(validBundle);
    assertNotNull(extraction);
    assertEquals(
        "should have stripped the 'callback' entry from the extracted bundle",
        3,
        extraction.second.keySet().size());
  }

  private Pair<JobCallback, Bundle> extractCallback(Bundle bundle) {
    return extractor.extractCallback(bundle);
  }

  private static final class BadParcelable implements Parcelable {
    public static final Parcelable.Creator<BadParcelable> CREATOR =
        new Parcelable.Creator<BadParcelable>() {
          @Override
          public BadParcelable createFromParcel(Parcel in) {
            return new BadParcelable(in);
          }

          @Override
          public BadParcelable[] newArray(int size) {
            return new BadParcelable[size];
          }
        };
    private final int num;

    public BadParcelable(int i) {
      num = i;
    }

    private BadParcelable(Parcel in) {
      num = in.readInt();
    }

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel dst, int flags) {
      dst.writeInt(num);
    }
  }
}
