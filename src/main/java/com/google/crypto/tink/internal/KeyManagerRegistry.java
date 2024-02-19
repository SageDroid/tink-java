// Copyright 2022 Google LLC
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

import com.google.crypto.tink.KeyManager;
import com.google.crypto.tink.config.internal.TinkFipsUtil;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * An internal API to register KeyManagers.
 *
 * <p>The KeyManagerRegistry provides an API to register KeyManagers, ensuring FIPS
 * compatibility. For registered managers, it gives access to the following operations:
 *
 * <ul>
 *   <li>Retrive KeyManagers
 * </ul>
 */
public final class KeyManagerRegistry {
  private static final Logger logger = Logger.getLogger(KeyManagerRegistry.class.getName());

  // A map from the TypeUrl to the KeyManagerContainer.
  private ConcurrentMap<String, KeyManagerContainer> keyManagerMap;
  // typeUrl -> newKeyAllowed mapping
  private ConcurrentMap<String, Boolean> newKeyAllowedMap;

  private static final KeyManagerRegistry GLOBAL_INSTANCE = new KeyManagerRegistry();

  /** Returns the global instance. */
  public static KeyManagerRegistry globalInstance() {
    return GLOBAL_INSTANCE;
  }

  /** Resets the global instance. Should only be used in tests. Not thread safe. */
  public static void resetGlobalInstanceTestOnly() {
    GLOBAL_INSTANCE.keyManagerMap = new ConcurrentHashMap<>();
    GLOBAL_INSTANCE.newKeyAllowedMap = new ConcurrentHashMap<>();
  }

  public KeyManagerRegistry(KeyManagerRegistry original) {
    keyManagerMap = new ConcurrentHashMap<>(original.keyManagerMap);
    newKeyAllowedMap = new ConcurrentHashMap<>(original.newKeyAllowedMap);
  }

  public KeyManagerRegistry() {
    keyManagerMap = new ConcurrentHashMap<>();
    newKeyAllowedMap = new ConcurrentHashMap<>();
  }

  /** A container which is constructed from {@link KeyManager}. */
  private static interface KeyManagerContainer {
    /**
     * Returns the KeyManager for the given primitive or throws if the given primitive is not in
     * supportedPrimitives.
     */
    <P> KeyManager<P> getKeyManager(Class<P> primitiveClass) throws GeneralSecurityException;

    /**
     * Returns a KeyManager from the given container.
     */
    KeyManager<?> getUntypedKeyManager();

    /**
     * The Class object corresponding to the actual KeyManager used to build this
     * object.
     */
    Class<?> getImplementingClass();

    /**
     * The primitives supported by the underlying {@link KeyManager}.
     */
    Set<Class<?>> supportedPrimitives();
  }

  private static <P> KeyManagerContainer createContainerFor(KeyManager<P> keyManager) {
    final KeyManager<P> localKeyManager = keyManager;
    return new KeyManagerContainer() {
      @Override
      public <Q> KeyManager<Q> getKeyManager(Class<Q> primitiveClass) {
        if (!localKeyManager.getPrimitiveClass().equals(primitiveClass)) {
          throw new InternalError(
              "This should never be called, as we always first check supportedPrimitives.");
        }
        @SuppressWarnings("unchecked") // We checked equality of the primitiveClass objects.
        KeyManager<Q> result = (KeyManager<Q>) localKeyManager;
        return result;
      }

      @Override
      public KeyManager<?> getUntypedKeyManager() {
        return localKeyManager;
      }

      @Override
      public Class<?> getImplementingClass() {
        return localKeyManager.getClass();
      }

      @Override
      public Set<Class<?>> supportedPrimitives() {
        return Collections.<Class<?>>singleton(localKeyManager.getPrimitiveClass());
      }
    };
  }

  private synchronized KeyManagerContainer getKeyManagerContainerOrThrow(String typeUrl)
      throws GeneralSecurityException {
    if (!keyManagerMap.containsKey(typeUrl)) {
      throw new GeneralSecurityException("No key manager found for key type " + typeUrl);
    }
    return keyManagerMap.get(typeUrl);
  }

  private synchronized void registerKeyManagerContainer(
      final KeyManagerContainer containerToInsert, boolean forceOverwrite, boolean newKeyAllowed)
      throws GeneralSecurityException {
    String typeUrl = containerToInsert.getUntypedKeyManager().getKeyType();
    if (newKeyAllowed && newKeyAllowedMap.containsKey(typeUrl) && !newKeyAllowedMap.get(typeUrl)) {
      throw new GeneralSecurityException("New keys are already disallowed for key type " + typeUrl);
    }
    KeyManagerContainer container = keyManagerMap.get(typeUrl);
    if (container != null
        && !container.getImplementingClass().equals(containerToInsert.getImplementingClass())) {
      logger.warning("Attempted overwrite of a registered key manager for key type " + typeUrl);
      throw new GeneralSecurityException(
          String.format(
              "typeUrl (%s) is already registered with %s, cannot be re-registered with %s",
              typeUrl,
              container.getImplementingClass().getName(),
              containerToInsert.getImplementingClass().getName()));
    }
    if (!forceOverwrite) {
      keyManagerMap.putIfAbsent(typeUrl, containerToInsert);
    } else {
      keyManagerMap.put(typeUrl, containerToInsert);
    }
    newKeyAllowedMap.put(typeUrl, newKeyAllowed);
  }

  /**
   * Attempts to insert the given KeyManager into the object.
   *
   * <p>If this fails, the KeyManagerRegistry is in an unspecified state and should be discarded.
   */
  public synchronized <P> void registerKeyManager(
      final KeyManager<P> manager, boolean newKeyAllowed) throws GeneralSecurityException {
    registerKeyManagerWithFipsCompatibility(
        manager, TinkFipsUtil.AlgorithmFipsCompatibility.ALGORITHM_NOT_FIPS, newKeyAllowed);
  }

  /**
   * Attempts to insert the given KeyManager into the object; the caller guarantees that the given
   * key manager satisfies the given FIPS compatibility.
   *
   * <p>If this fails, the KeyManagerRegistry is in an unspecified state and should be discarded.
   */
  public synchronized <P> void registerKeyManagerWithFipsCompatibility(
      final KeyManager<P> manager,
      TinkFipsUtil.AlgorithmFipsCompatibility compatibility,
      boolean newKeyAllowed)
      throws GeneralSecurityException {
    if (!compatibility.isCompatible()) {
      throw new GeneralSecurityException(
          "Cannot register key manager: FIPS compatibility insufficient");
    }
    registerKeyManagerContainer(
        createContainerFor(manager), /* forceOverwrite= */ false, newKeyAllowed);
  }

  public boolean typeUrlExists(String typeUrl) {
    return keyManagerMap.containsKey(typeUrl);
  }

  private static String toCommaSeparatedString(Set<Class<?>> setOfClasses) {
    StringBuilder b = new StringBuilder();
    boolean first = true;
    for (Class<?> clazz : setOfClasses) {
      if (!first) {
        b.append(", ");
      }
      b.append(clazz.getCanonicalName());
      first = false;
    }
    return b.toString();
  }

  /**
   * @return a {@link KeyManager} for the given {@code typeUrl} and {@code primitiveClass}(if found
   *     and this key type supports this primitive).
   */
  public <P> KeyManager<P> getKeyManager(String typeUrl, Class<P> primitiveClass)
      throws GeneralSecurityException {
    KeyManagerContainer container = getKeyManagerContainerOrThrow(typeUrl);
    if (container.supportedPrimitives().contains(primitiveClass)) {
      return container.getKeyManager(primitiveClass);
    }
    throw new GeneralSecurityException(
        "Primitive type "
            + primitiveClass.getName()
            + " not supported by key manager of type "
            + container.getImplementingClass()
            + ", supported primitives: "
            + toCommaSeparatedString(container.supportedPrimitives()));
  }

  /**
   * @return a {@link KeyManager} for the given {@code typeUrl} (if found).
   */
  public KeyManager<?> getUntypedKeyManager(String typeUrl) throws GeneralSecurityException {
    KeyManagerContainer container = getKeyManagerContainerOrThrow(typeUrl);
    return container.getUntypedKeyManager();
  }

  public boolean isNewKeyAllowed(String typeUrl) {
    return newKeyAllowedMap.get(typeUrl);
  }

  public boolean isEmpty() {
    return keyManagerMap.isEmpty();
  }

  /**
   * Restricts Tink to FIPS if this is the global instance.
   *
   * <p>We make this a member method (instead of a static one which gets the global instance)
   * because the call to "useOnlyFips" needs to happen under the same mutex lock which protects the
   * registerKeyManager methods.
   */
  public synchronized void restrictToFipsIfEmptyAndGlobalInstance()
      throws GeneralSecurityException {
    if (this != globalInstance()) {
      throw new GeneralSecurityException("Only the global instance can be restricted to FIPS.");
    }
    // If we are already using FIPS mode, do nothing.
    if (TinkFipsUtil.useOnlyFips()) {
      return;
    }

    if (!isEmpty()) {
      throw new GeneralSecurityException("Could not enable FIPS mode as Registry is not empty.");
    }
    TinkFipsUtil.setFipsRestricted();
  }
}
