// Copyright 2021 Google LLC
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

package com.google.crypto.tink.internal;

import com.google.crypto.tink.util.Bytes;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.annotation.Nullable;

/** Helper functions used throughout Tink, for Tink internal use only. */
public final class Util {
  /** Android 18-compatible alternative to {@link java.nio.charset.StandardCharsets#UTF_8}. */
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  /** Returns a positive random int which can be used as a key ID in a keyset. */
  public static int randKeyId() {
    SecureRandom secureRandom = new SecureRandom();
    byte[] rand = new byte[4];
    int result = 0;
    while (result == 0) {
      secureRandom.nextBytes(rand);
      // TODO(b/148124847): Other languages create key_ids with the MSB set, so we should here too.
      result =
          ((rand[0] & 0x7f) << 24)
              | ((rand[1] & 0xff) << 16)
              | ((rand[2] & 0xff) << 8)
              | (rand[3] & 0xff);
    }
    return result;
  }

  private static final byte toByteFromPrintableAscii(char c) {
    if (c < '!' || c > '~') {
      throw new TinkBugException("Not a printable ASCII character: " + c);
    }
    return (byte) c;
  }

  private static final byte checkedToByteFromPrintableAscii(char c)
      throws GeneralSecurityException {
    if (c < '!' || c > '~') {
      throw new GeneralSecurityException("Not a printable ASCII character: " + c);
    }
    return (byte) c;
  }

  /**
   * Converts a string {@code s} to a corresponding {@link Bytes} object.
   *
   * <p>The string must contain only printable ASCII characters; calling it in any other way is a
   * considered a bug in Tink. Spaces are not allowed.
   *
   * @throws TinkBugException if s contains a character which is not a printable ASCII character or
   *     a space.
   */
  public static final Bytes toBytesFromPrintableAscii(String s) {
    byte[] result = new byte[s.length()];
    for (int i = 0; i < s.length(); ++i) {
      result[i] = toByteFromPrintableAscii(s.charAt(i));
    }
    return Bytes.copyFrom(result);
  }

  /**
   * Converts a string {@code s} to a corresponding {@link Bytes} object.
   *
   * @throws GeneralSecurityException if s contains a character which is not a printable ASCII
   *     character or a space.
   */
  public static final Bytes checkedToBytesFromPrintableAscii(String s)
      throws GeneralSecurityException {
    byte[] result = new byte[s.length()];
    for (int i = 0; i < s.length(); ++i) {
      result[i] = checkedToByteFromPrintableAscii(s.charAt(i));
    }
    return Bytes.copyFrom(result);
  }

  /**
   * Best-effort checks that this is Android.
   *
   * <p>Note: this is more tricky than it seems. For example, using reflection to see if
   * android.os.Build.SDK_INT exists might fail because proguard might break the
   * reflection part. Using build dispatching can also fail if there are issues in the build graph
   * (see cl/510374081).
   *
   * @return true if running on Android.
   */
  public static boolean isAndroid() {
    // https://developer.android.com/reference/java/lang/System#getProperties%28%29
    return Objects.equals(System.getProperty("java.vendor"), "The Android Project");
  }

  /** Returns the current Android API level as integer or null if we do not run on Android. */
  @Nullable
  public static Integer getAndroidApiLevel() {
    if (!isAndroid()) {
      return null;
    }
    return BuildDispatchedCode.getApiLevel();
  }

  /** Returns true if the first argument is a prefix of the second argument. Not constant time. */
  public static boolean isPrefix(byte[] prefix, byte[] complete) {
    if (complete.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; ++i) {
      if (complete[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private Util() {}
}
